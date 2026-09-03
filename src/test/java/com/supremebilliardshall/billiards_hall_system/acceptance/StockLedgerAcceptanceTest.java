package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BACKEND-SPEC.md section 4, acceptance test 6, plus the reconciliation invariant.
//
// The ledger is the authority and product.qty_on_hand is a cache of it. A drift between the
// two is silent — every screen keeps working and shows a number that is quietly wrong — so it
// is asserted directly after a sequence that exercises every reason code.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StockLedgerAcceptanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PoolTableRepository poolTableRepository;

    @Autowired
    private PoolTableRateRepository poolTableRateRepository;

    @Autowired
    private CustomerTypeRepository customerTypeRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    private UUID branchId;
    private UUID userId;
    private UUID tableId;
    private UUID customerTypeId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("STOCKTEST");
        branch.setName("Stock Test Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername("stock-tester-" + UUID.randomUUID());
        user.setPasswordHash("unused");
        user.setFullName("Stock Tester");
        user.setRole(UserRole.ADMIN);
        user.setIsActive(true);
        userId = appUserRepository.saveAndFlush(user).getId();

        PoolTable poolTable = new PoolTable();
        poolTable.setBranchId(branchId);
        poolTable.setName("Table 1");
        poolTable.setTableNumber(1);
        poolTable.setIsActive(true);
        tableId = poolTableRepository.saveAndFlush(poolTable).getId();

        PoolTableRate rate = new PoolTableRate();
        rate.setBranchId(branchId);
        rate.setPoolTableId(tableId);
        rate.setRatePerMinute(new BigDecimal("4.0000"));
        rate.setEffectiveFrom(OffsetDateTime.now().minusDays(1));
        poolTableRateRepository.saveAndFlush(rate);

        CustomerType customerType = new CustomerType();
        customerType.setBranchId(branchId);
        customerType.setName("Regular");
        customerType.setAllowsRateOverride(false);
        customerType.setIsDefault(true);
        customerType.setSortOrder(1);
        customerTypeId = customerTypeRepository.saveAndFlush(customerType).getId();

        Product product = new Product();
        product.setBranchId(branchId);
        product.setName("San Miguel Pale Pilsen");
        product.setSellingPrice(new BigDecimal("90.00"));
        product.setAvgCost(BigDecimal.ZERO);
        product.setQtyOnHand(BigDecimal.ZERO);
        product.setIsActive(true);
        productId = productRepository.saveAndFlush(product).getId();
    }

    // Acceptance test 6.
    @Test
    void voidingALineRequiresAReasonAndReturnsTheStock() throws Exception {
        receiveDelivery("50", "52.50");
        UUID billId = openSessionAndGetBillId();
        UUID lineId = addLine(billId, "2");

        assertThat(qtyOnHand()).isEqualByComparingTo("48.000");

        // No reason at all, and a blank one, are both refused before anything moves.
        voidLine(billId, lineId, "{}").andExpect(status().isBadRequest());
        voidLine(billId, lineId, "{\"reason\":\"   \"}").andExpect(status().isBadRequest());
        assertThat(qtyOnHand()).isEqualByComparingTo("48.000");

        JsonNode voided = body(voidLine(billId, lineId, "{\"reason\":\"Customer changed order\"}")
                .andExpect(status().isOk())).get("data");

        // The line is retained, marked, and excluded from totals — never deleted.
        assertThat(voided.get("voidedAt").isNull()).isFalse();
        assertThat(voided.get("voidReason").asText()).isEqualTo("Customer changed order");

        // And the stock came back, as a compensating SALE_VOID row rather than an edit.
        assertThat(qtyOnHand()).isEqualByComparingTo("50.000");
        assertThat(reasons()).containsExactly(StockReason.DELIVERY, StockReason.SALE, StockReason.SALE_VOID);
    }

    @Test
    void theLedgerAndTheCachedQuantityAgreeAfterEveryKindOfMovement() throws Exception {
        receiveDelivery("50", "52.50");
        UUID billId = openSessionAndGetBillId();

        UUID firstLine = addLine(billId, "3");
        addLine(billId, "2");
        voidLine(billId, firstLine, "{\"reason\":\"Rang up twice\"}").andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/stock/comps")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":4,\"note\":\"Staff drinks\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/stock/corrections")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"newQuantity\":40,\"note\":\"Physical count\"}"))
                .andExpect(status().isOk());

        // 50 delivered, 3 and 2 sold, 3 returned by the void, 4 comped, then corrected to 40.
        assertThat(qtyOnHand()).isEqualByComparingTo("40.000");
        assertThat(reasons()).containsExactly(
                StockReason.DELIVERY, StockReason.SALE, StockReason.SALE,
                StockReason.SALE_VOID, StockReason.STAFF_COMP, StockReason.CORRECTION);

        // The invariant: the cache is exactly the sum of the ledger that produced it.
        assertThat(asUser(() -> stockMovementRepository.sumQuantityDelta(productId)))
                .isEqualByComparingTo(qtyOnHand());

        // And every stored running balance agrees with a replay of the ledger.
        BigDecimal running = BigDecimal.ZERO;
        for (StockMovement movement : asUser(() -> stockMovementRepository.findByProductId(productId).reversed())) {
            running = running.add(movement.getQuantityDelta());
            assertThat(movement.getQtyAfter()).isEqualByComparingTo(running);
        }
    }


    private void receiveDelivery(String quantity, String unitCost) throws Exception {
        mockMvc.perform(post("/api/v1/stock/deliveries")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"productId\":\"" + productId + "\",\"quantity\":" + quantity
                                + ",\"unitCost\":" + unitCost + "}]}"))
                .andExpect(status().isOk());
    }

    private UUID openSessionAndGetBillId() throws Exception {
        JsonNode opened = body(mockMvc.perform(post("/api/v1/sessions")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + customerTypeId + "\"}"))
                .andExpect(status().isOk())).get("data");
        return UUID.fromString(opened.get("billId").asText());
    }

    private UUID addLine(UUID billId, String quantity) throws Exception {
        JsonNode added = body(mockMvc.perform(post("/api/v1/bills/" + billId + "/lines")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk())).get("data");
        return UUID.fromString(added.get("line").get("id").asText());
    }

    private ResultActions voidLine(UUID billId, UUID lineId, String bodyJson) throws Exception {
        return mockMvc.perform(post("/api/v1/bills/" + billId + "/lines/" + lineId + "/void")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    private BigDecimal qtyOnHand() {
        return asUser(() -> productRepository.findById(productId).orElseThrow().getQtyOnHand());
    }

    private List<StockReason> reasons() {
        return asUser(() -> stockMovementRepository.findByProductId(productId).reversed()
                .stream().map(StockMovement::getReason).toList());
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private AppUserDetails principal() {
        return new AppUserDetails(userId, branchId, "stock-tester",
                "unused", "Stock Tester", UserRole.ADMIN, true);
    }

    // Direct repository access needs a security context, because the branch predicate reads
    // it. MockMvc clears the context after every request, so it is established per access
    // rather than once — getting that ordering wrong is silent, not loud.
    private <T> T asUser(java.util.function.Supplier<T> work) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal(), "unused", List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
