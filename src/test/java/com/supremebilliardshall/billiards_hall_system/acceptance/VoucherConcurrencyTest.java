package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.dto.voucher.RedeemVoucherRequestDTO;
import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import com.supremebilliardshall.billiards_hall_system.service.VoucherService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * Two tills, one code, at the same moment.
 *
 * This is the case a SELECT-then-UPDATE loses and the reason redemption is written as a
 * conditional UPDATE ... WHERE redeemed_at IS NULL. Both threads pass every refusal — neither
 * sees a redeemed_at, both bills are open, the code is good — and it is the write that
 * separates them: exactly one changes a row, and the other changes none.
 *
 * Written the way ReceiptNumberConcurrencyTest and StockConcurrencyTest are, and for their
 * stated reason: removing the guard broke nothing in the rest of the suite. A race is only
 * covered by a test that actually races.
 *
 * Deliberately NOT @Transactional — the threads must commit for there to be any contention —
 * so it works in its own throwaway branch and leaves its rows behind.
 */
@SpringBootTest
class VoucherConcurrencyTest {

    private static final int CONCURRENT_TILLS = 2;
    private static final int VOUCHER_MINUTES = 120;

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PoolTableRepository poolTableRepository;

    @Autowired
    private CustomerTypeRepository customerTypeRepository;

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private BillLineRepository billLineRepository;

    @Autowired
    private TableSessionRepository tableSessionRepository;

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private VoucherBatchRepository voucherBatchRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;
    private UUID branchId;
    private UUID userId;
    private UUID customerTypeId;
    private String code;
    // One bill per till. Sharing one would let the bill row serialise the threads before either
    // reached the voucher, and the race would never happen.
    private final List<UUID> billIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {
            Branch branch = new Branch();
            branch.setCode("VRACE-" + UUID.randomUUID().toString().substring(0, 8));
            branch.setName("Voucher Race Branch");
            branch.setNextReceiptNo(1L);
            // Inactive for ReceiptNumberConcurrencyTest's reason: this test commits outside a
            // transaction, so the branch outlives the run, and an active one would join the
            // real branch and break a global admin's single-active-branch resolution.
            branch.setIsActive(false);
            branchId = branchRepository.saveAndFlush(branch).getId();

            AppUser user = new AppUser();
            user.setBranchId(branchId);
            user.setUsername("voucher-race-" + UUID.randomUUID());
            user.setPasswordHash("unused");
            user.setFullName("Voucher Race Tester");
            user.setRole(UserRole.EMPLOYEE);
            user.setIsActive(true);
            userId = appUserRepository.saveAndFlush(user).getId();

            CustomerType customerType = new CustomerType();
            customerType.setBranchId(branchId);
            customerType.setName("Regular");
            customerType.setAllowsRateOverride(false);
            customerType.setIsDefault(true);
            customerType.setSortOrder(1);
            customerTypeId = customerTypeRepository.saveAndFlush(customerType).getId();

            VoucherBatch batch = new VoucherBatch();
            batch.setBranchId(branchId);
            batch.setMinutes(VOUCHER_MINUTES);
            batch.setQuantity(1);
            batch.setExpiresOn(LocalDate.now().plusMonths(1));
            batch.setCreatedBy(userId);
            UUID batchId = voucherBatchRepository.saveAndFlush(batch).getId();

            Voucher voucher = new Voucher();
            voucher.setBranchId(branchId);
            voucher.setBatchId(batchId);
            voucher.setCode("SBRACE" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
            voucher.setMinutes(VOUCHER_MINUTES);
            voucher.setExpiresOn(LocalDate.now().plusMonths(1));
            code = voucherRepository.saveAndFlush(voucher).getCode();

            billIds.clear();
            for (int i = 0; i < CONCURRENT_TILLS; i++) {
                billIds.add(givenClosedSessionBill(i + 1));
            }
        });
    }

    @Test
    void twoTillsRedeemingOneCodeLeaveExactlyOneWinner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_TILLS);
        CountDownLatch startTogether = new CountDownLatch(1);

        List<Future<String>> futures = new ArrayList<>();
        for (UUID billId : billIds) {
            futures.add(executor.submit(() -> {
                startTogether.await();
                return asUser(() -> {
                    try {
                        transactionTemplate.execute(status ->
                                voucherService.redeem(billId, request(code)));
                        return "REDEEMED";
                    } catch (RuntimeException e) {
                        // The refusal, whichever half of it fired: the pre-check on a thread
                        // that read the row after the winner committed, or zero rows affected
                        // on one that read it before. Both say the same thing to the cashier.
                        return e.getMessage();
                    }
                });
            }));
        }

        startTogether.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // EXACTLY ONE. Without the conditional update both tills succeed and one code pays for
        // two customers' tables.
        assertThat(outcomes.stream().filter("REDEEMED"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> !"REDEEMED".equals(outcome)).toList())
                .allSatisfy(message -> assertThat(message).contains("already redeemed"));

        // And the code carries one redemption, against one bill.
        Voucher voucher = asUser(() -> transactionTemplate.execute(status ->
                voucherRepository.findByCode(code).orElseThrow()));
        assertThat(voucher.getRedeemedAt()).isNotNull();
        assertThat(voucher.getRedeemedBillId()).isNotNull();

        // The loser's bill was not charged for a voucher it never got.
        List<Bill> charged = asUser(() -> transactionTemplate.execute(status ->
                billIds.stream()
                        .map(id -> billRepository.findById(id).orElseThrow())
                        .filter(bill -> bill.getVoucherAmount().signum() > 0)
                        .toList()));
        assertThat(charged).hasSize(1);
        assertThat(charged.getFirst().getId()).isEqualTo(voucher.getRedeemedBillId());
    }

    // ---- fixtures ------------------------------------------------------------------

    /*
     * A bill carrying one closed session and one TIME line, built directly rather than through
     * the session API.
     *
     * The API path would need a distinct pool table per till anyway — one open session per
     * table is a unique index — and this test is about the voucher write, not about closing a
     * table. 180 minutes at 4.0000/min is 720.00, so a two-hour voucher covers 480.00 of it.
     */
    private UUID givenClosedSessionBill(int tableNumber) {
        PoolTable poolTable = new PoolTable();
        poolTable.setBranchId(branchId);
        poolTable.setName("Race Table " + tableNumber);
        poolTable.setTableNumber(tableNumber);
        poolTable.setIsActive(true);
        UUID tableId = poolTableRepository.saveAndFlush(poolTable).getId();

        Bill bill = new Bill();
        bill.setBranchId(branchId);
        bill.setStatus(BillStatus.OPEN);
        bill.setCustomerTypeId(customerTypeId);
        bill.setOpenedBy(userId);
        bill.setSubtotalTime(BigDecimal.ZERO);
        bill.setSubtotalItems(BigDecimal.ZERO);
        bill.setTotalAmount(BigDecimal.ZERO);
        bill.setTotalCost(BigDecimal.ZERO);
        UUID billId = billRepository.saveAndFlush(bill).getId();

        /*
         * Opened first, then closed by a second statement.
         *
         * opened_at carries @CreationTimestamp, so the insert stamps it from the clock and
         * ignores anything set here -- which puts it microseconds AFTER a closed_at computed a
         * line earlier, and table_session_close_order_chk refuses that row. Back-dating with a
         * native update is the same shape the other acceptance tests use to age a session, and
         * it leaves a row saying what actually happened: opened three hours ago, closed now.
         */
        TableSession session = new TableSession();
        session.setBranchId(branchId);
        session.setBillId(billId);
        session.setPoolTableId(tableId);
        session.setCustomerTypeId(customerTypeId);
        session.setStatus(SessionStatus.OPEN);
        session.setOpenedBy(userId);
        // Written explicitly: the column is NOT NULL with a database default, but Hibernate
        // writes every mapped column on insert, so an unset Boolean goes down as null.
        session.setNeedsReview(false);
        session.setStandardRatePerMinute(new BigDecimal("4.0000"));
        UUID sessionId = tableSessionRepository.saveAndFlush(session).getId();

        entityManager.createNativeQuery(
                        "update table_session"
                        + "   set opened_at = now() - make_interval(mins => 180),"
                        + "       status = 'CLOSED', closed_at = now(), closed_by = :actor,"
                        + "       close_kind = 'MANUAL', billed_minutes = 180,"
                        + "       time_amount = 720.00"
                        + " where id = :id")
                .setParameter("actor", userId)
                .setParameter("id", sessionId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        BillLine line = new BillLine();
        line.setBranchId(branchId);
        line.setBillId(billId);
        line.setLineKind(BillLineKind.TIME);
        line.setSeq(1);
        line.setSessionId(sessionId);
        line.setDescription("Race Table " + tableNumber + " - 180 min @ 4.00/min");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("720.00"));
        line.setUnitCost(BigDecimal.ZERO);
        line.setBilledMinutes(180);
        line.setCreatedBy(userId);
        billLineRepository.saveAndFlush(line);

        return billId;
    }

    private RedeemVoucherRequestDTO request(String voucherCode) {
        RedeemVoucherRequestDTO requestDto = new RedeemVoucherRequestDTO();
        requestDto.setCode(voucherCode);
        return requestDto;
    }

    private <T> T asUser(Supplier<T> work) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                new AppUserDetails(userId, branchId, "voucher-race", "unused",
                        "Voucher Race Tester", UserRole.EMPLOYEE, true), "unused", List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
