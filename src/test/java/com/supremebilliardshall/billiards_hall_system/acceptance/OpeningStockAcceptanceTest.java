package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import com.supremebilliardshall.billiards_hall_system.service.StockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// No test transaction: each HTTP write commits before JDBC reads the persisted ledger/cache.
@SpringBootTest
@AutoConfigureMockMvc
class OpeningStockAcceptanceTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @MockitoSpyBean private StockService stockService;

    private UUID branchId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("OPEN-" + UUID.randomUUID().toString().substring(0, 8));
        branch.setName("Opening stock test");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branches.saveAndFlush(branch).getId();
        AppUser actor = new AppUser();
        actor.setBranchId(branchId);
        actor.setUsername("opening-" + UUID.randomUUID());
        actor.setPasswordHash("unused");
        actor.setFullName("Stock owner");
        actor.setRole(UserRole.ADMIN);
        actor.setIsActive(true);
        actorId = users.saveAndFlush(actor).getId();
    }

    @AfterEach
    void deactivateFixtureAdmin() {
        // These committed fixtures must not change other tests' system-wide last-admin count.
        jdbc.update("UPDATE app_user SET is_active = false WHERE id = ?", actorId);
    }

    @Test
    void openingDeliveryPersistsCostQuantityActorAndLedgerAndFeedsTheNextAverage() throws Exception {
        UUID productId = create("{\"quantity\":12,\"unitCost\":62.5}");
        assertProduct(productId, "12", "62.5");
        var movement = jdbc.queryForMap("SELECT * FROM stock_movement WHERE product_id = ?", productId);
        assertThat(movement.get("reason").toString()).isEqualTo("DELIVERY");
        assertThat((BigDecimal) movement.get("quantity_delta")).isEqualByComparingTo("12");
        assertThat((BigDecimal) movement.get("qty_after")).isEqualByComparingTo("12");
        assertThat((BigDecimal) movement.get("unit_cost")).isEqualByComparingTo("62.5");
        assertThat(movement.get("actor_id")).isEqualTo(actorId);
        assertThat(movement.get("branch_id")).isEqualTo(branchId);
        var delivery = jdbc.queryForMap("SELECT * FROM stock_delivery WHERE id = ?", movement.get("delivery_id"));
        assertThat(delivery.get("received_by")).isEqualTo(actorId);
        assertThat(delivery.get("note")).isEqualTo("Opening stock");
        mockMvc.perform(post("/api/v1/stock/deliveries").with(user(principal(UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"lines":[{"productId":"%s","quantity":12,"unitCost":67.5}]}
                        """.formatted(productId)))
                .andExpect(status().isOk());
        assertProduct(productId, "24", "65");
        assertThat(jdbc.queryForObject("SELECT sum(quantity_delta) FROM stock_movement WHERE product_id = ?",
                BigDecimal.class, productId)).isEqualByComparingTo("24");
        mockMvc.perform(get("/api/v1/products").with(user(principal(UserRole.EMPLOYEE))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].avgCost").doesNotExist());
    }

    @Test
    void emptyOpeningStockLeavesZeroStockAndNoDelivery() throws Exception {
        UUID productId = create(null);
        assertProduct(productId, "0", "0");
        assertThat(count("stock_movement")).isZero();
        assertThat(count("stock_delivery")).isZero();
    }

    @Test
    void zeroUnitCostIsAllowedForFreeOpeningStock() throws Exception {
        UUID productId = create("{\"quantity\":1.5,\"unitCost\":0}");
        assertProduct(productId, "1.5", "0");
        assertThat(count("stock_movement")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantity\":2}", "{\"unitCost\":10}",
            "{\"quantity\":0,\"unitCost\":10}", "{\"quantity\":-1,\"unitCost\":10}",
            "{\"quantity\":1,\"unitCost\":-1}", "{\"quantity\":0.0001,\"unitCost\":10}"})
    void rejectsIncompleteOrInvalidOpeningStockWithoutCreatingAnything(String opening) throws Exception {
        mockMvc.perform(post("/api/v1/products").with(user(principal(UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(opening)))
                .andExpect(status().isBadRequest());
        assertThat(count("product")).isZero();
        assertThat(count("stock_delivery")).isZero();
    }

    @Test
    void deliveryFailureRollsBackProductAuditAndStockTogether() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new BusinessRuleException("Simulated delivery failure");
        }).when(stockService).receiveDelivery(any());
        mockMvc.perform(post("/api/v1/products").with(user(principal(UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(body("{\"quantity\":12,\"unitCost\":62.5}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Simulated delivery failure"));
        assertThat(count("product")).isZero();
        assertThat(count("audit_log")).isZero();
        assertThat(count("stock_delivery")).isZero();
        assertThat(count("stock_movement")).isZero();
    }

    @Test
    void editCannotReplayOpeningStock() throws Exception {
        UUID productId = create("{\"quantity\":12,\"unitCost\":62.5}");
        mockMvc.perform(put("/api/v1/products/{id}", productId).with(user(principal(UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(body("{\"quantity\":5,\"unitCost\":10}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(
                        "Opening stock is only for new products. Use Add stock to receive a delivery."));
        assertProduct(productId, "12", "62.5");
        assertThat(count("stock_movement")).isEqualTo(1);
    }

    @Test
    void employeeCannotCreateOpeningStock() throws Exception {
        mockMvc.perform(post("/api/v1/products").with(user(principal(UserRole.EMPLOYEE)))
                        .contentType(MediaType.APPLICATION_JSON).content(body("{\"quantity\":1,\"unitCost\":10}")))
                .andExpect(status().isForbidden());
        assertThat(count("product")).isZero();
    }

    private UUID create(String opening) throws Exception {
        String response = mockMvc.perform(post("/api/v1/products").with(user(principal(UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(opening)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("data").get("id").asText());
    }

    private String body(String opening) {
        return "{\"name\":\"Opening beer\",\"sellingPrice\":90"
                + (opening == null ? "" : ",\"openingStock\":" + opening) + "}";
    }

    private void assertProduct(UUID id, String quantity, String cost) {
        var row = jdbc.queryForMap("SELECT qty_on_hand, avg_cost FROM product WHERE id = ?", id);
        assertThat((BigDecimal) row.get("qty_on_hand")).isEqualByComparingTo(quantity);
        assertThat((BigDecimal) row.get("avg_cost")).isEqualByComparingTo(cost);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE branch_id = ?", Integer.class, branchId);
    }

    private AppUserDetails principal(UserRole role) {
        return new AppUserDetails(actorId, branchId, "stock-owner", "unused", "Stock owner", role, true);
    }
}
