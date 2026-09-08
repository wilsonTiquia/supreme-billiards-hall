package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.dto.bill.AddBillLineRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleRequestDTO;
import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import com.supremebilliardshall.billiards_hall_system.service.CheckoutService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// Decision 2: receipt_no is allocated from branch.next_receipt_no, so two simultaneous
// checkouts reading it before either writes would hand two customers the same number. The
// SELECT ... FOR UPDATE on the branch row is what serialises them.
//
// This exists because removing that lock broke nothing: every other test in the suite still
// passed. A race is only covered by a test that actually races.
//
// Not @Transactional, for the same reason as StockConcurrencyTest: the threads must commit.
@SpringBootTest
class ReceiptNumberConcurrencyTest {

    private static final int CONCURRENT_CHECKOUTS = 8;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CustomerTypeRepository customerTypeRepository;

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private BillLineRepository billLineRepository;

    // A native count rather than a repository method: "exactly one payment row" is the
    // assertion, and adding countByBillId to production code only a test would call is the
    // wrong direction.
    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private UUID branchId;
    private UUID userId;
    // One product per thread. Sharing one would let the product row lock serialise the
    // threads before they ever reach the receipt counter, and the race would never happen.
    private final List<UUID> productIds = new ArrayList<>();
    private UUID customerTypeId;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {
            Branch branch = new Branch();
            branch.setCode("RCPT-" + UUID.randomUUID().toString().substring(0, 8));
            branch.setName("Receipt Number Concurrency Branch");
            branch.setNextReceiptNo(1L);
            // Inactive deliberately. This test commits outside a transaction, so the branch
            // outlives the run; left active it would join the real one and defeat the
            // single-active-branch default a global admin relies on, giving the owner
            // "No branch selected" on every call. The test's principals are bound to this
            // branch explicitly, so being inactive changes nothing here.
            branch.setIsActive(false);
            branchId = branchRepository.saveAndFlush(branch).getId();

            AppUser user = new AppUser();
            user.setBranchId(branchId);
            user.setUsername("receipt-tester-" + UUID.randomUUID());
            user.setPasswordHash("unused");
            user.setFullName("Receipt Tester");
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

            productIds.clear();
            for (int i = 0; i < CONCURRENT_CHECKOUTS; i++) {
                Product product = new Product();
                product.setBranchId(branchId);
                product.setName("Contended Softdrink " + UUID.randomUUID());
                product.setSellingPrice(new BigDecimal("50.00"));
                product.setAvgCost(new BigDecimal("30.0000"));
                product.setQtyOnHand(new BigDecimal("500.000"));
                product.setIsActive(true);
                productIds.add(productRepository.saveAndFlush(product).getId());
            }
        });
    }

    @Test
    void concurrentCheckoutsEachTakeTheirOwnReceiptNumber() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_CHECKOUTS);
        CountDownLatch startTogether = new CountDownLatch(1);

        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_CHECKOUTS; i++) {
            String idempotencyKey = "receipt-race-" + i + "-" + UUID.randomUUID();
            UUID productId = productIds.get(i);
            futures.add(executor.submit(() -> {
                startTogether.await();
                return asUser(() -> transactionTemplate.execute(status ->
                        checkoutService.quickSale(quickSale(idempotencyKey, productId)).getReceiptNo()));
            }));
        }

        startTogether.countDown();
        List<Long> receiptNumbers = new ArrayList<>();
        for (Future<Long> future : futures) {
            receiptNumbers.add(future.get(30, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // Eight checkouts, eight DIFFERENT receipt numbers, 1 through 8. Without the row lock
        // several customers walk out holding the same number.
        assertThat(receiptNumbers).hasSize(CONCURRENT_CHECKOUTS);
        assertThat(receiptNumbers.stream().distinct().count()).isEqualTo(CONCURRENT_CHECKOUTS);
        assertThat(receiptNumbers.stream().sorted().toList())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);

        // And the counter is left where the next sale expects it.
        Long nextReceiptNo = asUser(() -> transactionTemplate.execute(status ->
                branchRepository.findById(branchId).orElseThrow().getNextReceiptNo()));
        assertThat(nextReceiptNo).isEqualTo(CONCURRENT_CHECKOUTS + 1L);
    }

    /*
     * Two tabs on ONE bill. Exactly one takes the money; the other must be told why.
     *
     * The money was never at risk -- one payment row was written either way -- but the loser
     * used to get a bare HTTP 500 with no message, on the one screen where "did that go
     * through?" has to be answerable. Both tabs read version 0 and both passed the version
     * check, because neither had written yet; the collision landed at the flush, where
     * bill.business_date being @Generated(INSERT, UPDATE) turned a zero-row update into
     * "The database returned no natively generated values" rather than an optimistic-lock
     * failure the handler maps.
     *
     * The bill row lock is what makes the loser re-read the bumped version and fail the
     * ordinary check instead.
     */
    @Test
    void twoTabsCheckingOutOneBillLeaveExactlyOneWinnerAndACleanRefusal() throws Exception {
        UUID billId = asUser(() -> transactionTemplate.execute(status -> openBill()));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startTogether = new CountDownLatch(1);

        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String idempotencyKey = "one-bill-race-" + i + "-" + UUID.randomUUID();
            futures.add(executor.submit(() -> {
                startTogether.await();
                try {
                    return asUser(() -> transactionTemplate.execute(status ->
                            checkoutService.pay(billId, payment(idempotencyKey))));
                } catch (Exception ex) {
                    return ex;
                }
            }));
        }
        startTogether.countDown();

        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        executor.shutdown();

        List<Object> paid = outcomes.stream().filter(PaymentResponseDTO.class::isInstance).toList();
        List<Object> refused = outcomes.stream().filter(Exception.class::isInstance).toList();

        assertThat(paid).hasSize(1);
        assertThat(refused).hasSize(1);

        /*
         * The refusal is a mapped domain exception carrying a message the counter can act on.
         *
         * It is "Bill is already CLOSED", not STALE_BILL_VERSION, and that is the lock working
         * rather than a gap: the loser now blocks until the winner COMMITS, so when it re-reads
         * the bill the sale is finished, and checkoutBlockers answers before the version check
         * is reached. "Somebody already took this payment" is the more useful sentence of the
         * two. STALE_BILL_VERSION still fires where it is the true answer -- another tab
         * discounting or redeeming a voucher bumps the version without closing the bill -- and
         * CheckoutAcceptanceTest.aStaleBillVersionIsRejected covers it.
         */
        List<Throwable> chain = causalChain((Exception) refused.get(0));
        assertThat(chain).anyMatch(BusinessRuleException.class::isInstance);
        assertThat(chain.stream().filter(BusinessRuleException.class::isInstance).findFirst().orElseThrow())
                .hasMessageContaining("Bill is already CLOSED");

        /*
         * The actual regression guard. The bug was not that the loser failed -- it always did,
         * and one payment row was always written -- but that it failed as an UNMAPPED
         * JpaSystemException ("The database returned no natively generated values"), which the
         * handler has no branch for and which reached the cashier as a bare 500 with no
         * message, on the one screen where "did that go through?" must be answerable.
         */
        assertThat(chain).noneMatch(JpaSystemException.class::isInstance);
        assertThat(chain).noneMatch(t -> t.getMessage() != null
                && t.getMessage().contains("no natively generated values"));

        // And the money moved exactly once.
        Number payments = asUser(() -> transactionTemplate.execute(status ->
                (Number) entityManager
                        .createNativeQuery("select count(*) from payment where bill_id = :billId")
                        .setParameter("billId", billId)
                        .getSingleResult()));
        assertThat(payments.longValue()).isEqualTo(1L);
    }

    // Spring wraps service exceptions on the way out of the transaction template, so the
    // interesting one is somewhere in the chain rather than always at the top or the bottom.
    private List<Throwable> causalChain(Throwable thrown) {
        List<Throwable> chain = new ArrayList<>();
        Throwable current = thrown;
        while (current != null && !chain.contains(current)) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }

    // An ordinary counter bill: OPEN, one product line, no session on it.
    private UUID openBill() {
        Bill bill = new Bill();
        bill.setBranchId(branchId);
        bill.setStatus(BillStatus.OPEN);
        bill.setCustomerTypeId(customerTypeId);
        bill.setOpenedBy(userId);
        bill.setSubtotalTime(BigDecimal.ZERO);
        bill.setSubtotalItems(BigDecimal.ZERO);
        bill.setTotalAmount(BigDecimal.ZERO);
        bill.setTotalCost(BigDecimal.ZERO);
        bill.setVersion(0);
        UUID billId = billRepository.saveAndFlush(bill).getId();

        BillLine line = new BillLine();
        line.setBranchId(branchId);
        line.setBillId(billId);
        line.setLineKind(BillLineKind.PRODUCT);
        line.setSeq(1);
        line.setProductId(productIds.get(0));
        line.setDescription("Contended Softdrink");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("50.00"));
        line.setUnitCost(new BigDecimal("30.0000"));
        line.setCreatedBy(userId);
        billLineRepository.saveAndFlush(line);
        return billId;
    }

    private PaymentRequestDTO payment(String idempotencyKey) {
        PaymentRequestDTO payment = new PaymentRequestDTO();
        payment.setMethod(PaymentMethod.CASH);
        payment.setAmount(new BigDecimal("50.00"));
        payment.setTendered(new BigDecimal("100.00"));
        payment.setIdempotencyKey(idempotencyKey);
        payment.setBillVersion(0);
        return payment;
    }

    private QuickSaleRequestDTO quickSale(String idempotencyKey, UUID productId) {
        PaymentRequestDTO payment = new PaymentRequestDTO();
        payment.setMethod(PaymentMethod.CASH);
        payment.setAmount(new BigDecimal("50.00"));
        payment.setTendered(new BigDecimal("100.00"));
        payment.setIdempotencyKey(idempotencyKey);
        payment.setBillVersion(0);

        return new QuickSaleRequestDTO(customerTypeId,
                List.of(new AddBillLineRequestDTO(productId, BigDecimal.ONE)), payment);
    }

    private <T> T asUser(Supplier<T> work) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                new AppUserDetails(userId, branchId, "receipt-tester", "unused",
                        "Receipt Tester", UserRole.EMPLOYEE, true), "unused", List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
