package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.repository.ProductRepository;
import com.supremebilliardshall.billiards_hall_system.repository.StockMovementRepository;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import com.supremebilliardshall.billiards_hall_system.service.StockService;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// Decision 1: qty_after is a running balance, so two concurrent movements that read the
// quantity before either writes would both compute the same value and the ledger would stop
// reconciling. SELECT ... FOR UPDATE on the product is what serialises them.
//
// Deliberately NOT @Transactional: the threads need to commit independently, or there is no
// contention to test. That means this test leaves its rows behind — stock_movement is
// append-only by trigger and cannot be cleaned up — so it works in its own throwaway branch.
@SpringBootTest
class StockConcurrencyTest {

    private static final int CONCURRENT_SALES = 8;

    @Autowired
    private StockService stockService;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private com.supremebilliardshall.billiards_hall_system.repository.StockDeliveryRepository stockDeliveryRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private UUID branchId;
    private UUID userId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {
            Branch branch = new Branch();
            branch.setCode("CONC-" + UUID.randomUUID().toString().substring(0, 8));
            branch.setName("Stock Concurrency Test Branch");
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
            user.setUsername("concurrency-tester-" + UUID.randomUUID());
            user.setPasswordHash("unused");
            user.setFullName("Concurrency Tester");
            user.setRole(UserRole.EMPLOYEE);
            user.setIsActive(true);
            userId = appUserRepository.saveAndFlush(user).getId();

            Product product = new Product();
            product.setBranchId(branchId);
            product.setName("Contended Beer " + UUID.randomUUID());
            product.setSellingPrice(new BigDecimal("90.00"));
            product.setAvgCost(new BigDecimal("52.5000"));
            product.setQtyOnHand(BigDecimal.ZERO);
            product.setIsActive(true);
            productId = productRepository.saveAndFlush(product).getId();
        });

        // The opening stock arrives as a DELIVERY movement, not as a number written straight
        // onto the product. Seeding the cache without a matching ledger row would make the two
        // disagree from the start, and the reconciliation assertion below meaningless.
        asUser(() -> transactionTemplate.execute(status -> {
            StockDelivery delivery = new StockDelivery();
            delivery.setBranchId(branchId);
            delivery.setReceivedBy(userId);
            delivery.setReference("Opening stock");
            UUID deliveryId = stockDeliveryRepository.saveAndFlush(delivery).getId();

            Product locked = stockService.lockProduct(productId);
            return stockService.applyMovement(locked, StockReason.DELIVERY, new BigDecimal("100.000"),
                    new BigDecimal("52.5000"), null, deliveryId, null).getId();
        }));
    }

    @Test
    void concurrentMovementsProduceOneRunningBalancePerRow() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_SALES);
        CountDownLatch startTogether = new CountDownLatch(1);

        List<Future<UUID>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < CONCURRENT_SALES; i++) {
            futures.add(executor.submit(() -> {
                // Released all at once, so the threads actually contend for the row.
                startTogether.await();
                return asUser(() -> transactionTemplate.execute(status -> {
                    // Lock and movement inside ONE transaction: the lock is only held until
                    // that transaction commits, so splitting them would defeat it entirely.
                    Product locked = stockService.lockProduct(productId);
                    return stockService.applyMovement(locked, StockReason.STAFF_COMP,
                            new BigDecimal("-1.000"), null, null, null, "Concurrent comp").getId();
                }));
            }));
        }

        startTogether.countDown();
        for (Future<UUID> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        BigDecimal cached = asUser(() -> transactionTemplate.execute(status ->
                productRepository.findById(productId).orElseThrow().getQtyOnHand()));
        BigDecimal ledgerSum = asUser(() -> transactionTemplate.execute(status ->
                stockMovementRepository.sumQuantityDelta(productId)));
        List<BigDecimal> balances = asUser(() -> transactionTemplate.execute(status ->
                stockMovementRepository.findByProductId(productId).stream()
                        .filter(movement -> movement.getReason() == StockReason.STAFF_COMP)
                        .map(StockMovement::getQtyAfter).toList()));

        assertThat(cached).isEqualByComparingTo("92.000");
        assertThat(ledgerSum).isEqualByComparingTo("92.000");

        // The real assertion: eight movements, eight DIFFERENT running balances, 99 down to 92.
        // Without the row lock two threads read 100 and both write 99, and this is where it shows.
        assertThat(balances).hasSize(CONCURRENT_SALES);
        assertThat(balances.stream().map(BigDecimal::stripTrailingZeros).distinct().count())
                .isEqualTo(CONCURRENT_SALES);
        assertThat(balances.stream().map(b -> b.setScale(0)).map(BigDecimal::intValue).sorted().toList())
                .containsExactly(92, 93, 94, 95, 96, 97, 98, 99);
    }

    private <T> T asUser(Supplier<T> work) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                new AppUserDetails(userId, branchId, "concurrency-tester", "unused",
                        "Concurrency Tester", UserRole.EMPLOYEE, true), "unused", List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
