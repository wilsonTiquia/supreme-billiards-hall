package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.dto.bill.AddBillLineRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleRequestDTO;
import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import com.supremebilliardshall.billiards_hall_system.service.CheckoutService;
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
