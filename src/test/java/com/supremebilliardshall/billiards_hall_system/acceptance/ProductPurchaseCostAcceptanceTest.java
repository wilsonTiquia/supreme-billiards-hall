package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// No test transaction: every assertion below reads committed state from a separate request/JDBC.
@SpringBootTest
@AutoConfigureMockMvc
class ProductPurchaseCostAcceptanceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired BranchRepository branches;
    @Autowired AppUserRepository users;
    @Autowired CustomerTypeRepository customerTypes;
    UUID branchId;
    UUID actorId;

    @BeforeEach void setup() {
        Branch branch = new Branch();
        branch.setCode("COST-" + UUID.randomUUID().toString().substring(0, 8));
        branch.setName("Purchase cost tests");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branches.saveAndFlush(branch).getId();
        AppUser actor = new AppUser();
        actor.setBranchId(branchId);
        actor.setUsername("cost-" + UUID.randomUUID());
        actor.setPasswordHash("unused");
        actor.setFullName("Cost owner");
        actor.setRole(UserRole.ADMIN);
        actor.setIsActive(true);
        actorId = users.saveAndFlush(actor).getId();
    }
    @AfterEach void deactivate() {
        jdbc.update("UPDATE app_user SET is_active = false WHERE id = ?", actorId);
    }

    @Test void unsetNeverBecomesZeroAndFreeStockRequiresConfirmation() throws Exception {
        UUID id = create("");
        assertThat(cost(id)).isNull();
        stock(id, "1", "0").andExpect(status().isConflict());
        edit(id, "\"defaultPurchaseCost\":0").andExpect(status().isConflict());
        assertThat(cost(id)).isNull();
        assertThat(movementCount(id)).isZero();
        edit(id, "\"defaultPurchaseCost\":0,\"confirmZeroDefaultCost\":true").andExpect(status().isOk());
        assertThat(cost(id)).isEqualByComparingTo("0");
        // Even saving an existing zero is an explicit choice, not an inferred confirmation.
        edit(id, "\"defaultPurchaseCost\":0").andExpect(status().isConflict());
        stock(id, "1.125", "0").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.movements[0].unitCost").value(0));
        edit(id, "\"defaultPurchaseCost\":null").andExpect(status().isOk());
        assertThat(cost(id)).isNull();
        stock(id, "1", "0").andExpect(status().isConflict());
        assertThat(movementCount(id)).isEqualTo(1);
    }

    @Test void rejectsUnconfirmedZeroOnCreationWithoutWritingProduct() throws Exception {
        mvc.perform(post("/api/v1/products").with(user(principal(UserRole.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON).content(body("\"defaultPurchaseCost\":0")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product WHERE branch_id = ?", Integer.class, branchId)).isZero();
        UUID free = create("\"defaultPurchaseCost\":0,\"confirmZeroDefaultCost\":true");
        assertThat(cost(free)).isEqualByComparingTo("0");
    }

    @Test void editsNeverRevalueInventoryOrHistoricalSalesAndDeliveryUsesCurrentDefault() throws Exception {
        UUID id = create("\"defaultPurchaseCost\":20,\"openingStock\":{\"quantity\":10,\"unitCost\":10}");
        CustomerType type = new CustomerType();
        type.setBranchId(branchId); type.setName("Regular"); type.setIsDefault(true);
        type.setAllowsRateOverride(false); type.setSortOrder(1);
        UUID customer = customerTypes.saveAndFlush(type).getId();
        mvc.perform(post("/api/v1/quick-sales").with(user(principal(UserRole.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"customerTypeId":"%s","lines":[{"productId":"%s","quantity":1}],
                 "payment":{"method":"CASH","amount":90,"tendered":90,"billVersion":0,"idempotencyKey":"%s"}}
                """.formatted(customer, id, UUID.randomUUID())))
                .andExpect(status().isOk());
        String receipt = jdbc.queryForObject("SELECT payload::text FROM receipt WHERE branch_id = ?", String.class, branchId);
        edit(id, "\"defaultPurchaseCost\":30").andExpect(status().isOk());
        assertProduct(id, "9", "10");
        stock(id, "9", "20").andExpect(status().isConflict());
        assertThat(movementCount(id)).isEqualTo(2); // opening + sale; rejected delivery creates nothing
        stock(id, "9", "30").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.movements[0].unitCost").value(30));
        assertProduct(id, "18", "20");
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM bill_line WHERE product_id = ?", BigDecimal.class, id)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT payload::text FROM receipt WHERE branch_id = ?", String.class, branchId)).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT sum(quantity_delta) FROM stock_movement WHERE product_id = ?", BigDecimal.class, id)).isEqualByComparingTo("18");
        assertThat(jdbc.queryForObject("SELECT \"after\"->>'defaultPurchaseCost' FROM audit_log WHERE entity_id = ? AND action = 'PRODUCT_UPDATED'", String.class, id)).isEqualTo("30");
        // Bulk actual prices remain independent and do not overwrite the saved default.
        mvc.perform(post("/api/v1/stock/deliveries").with(user(principal(UserRole.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"lines":[{"productId":"%s","quantity":18,"unitCost":40}]}
                """.formatted(id))).andExpect(status().isOk());
        assertProduct(id, "36", "30");
        assertThat(cost(id)).isEqualByComparingTo("30");
    }

    @Test void costIsAdminOnlyAndCrossBranchShortcutCannotWrite() throws Exception {
        UUID id = create("\"defaultPurchaseCost\":12.3456");
        mvc.perform(get("/api/v1/products").with(user(principal(UserRole.ADMIN))))
                .andExpect(jsonPath("$.data[0].defaultPurchaseCost").value(12.3456));
        mvc.perform(get("/api/v1/products").with(user(principal(UserRole.EMPLOYEE))))
                .andExpect(jsonPath("$.data[0].defaultPurchaseCost").doesNotExist())
                .andExpect(jsonPath("$.data[0].avgCost").doesNotExist());
        mvc.perform(put("/api/v1/products/{id}", id).with(user(principal(UserRole.EMPLOYEE)))
                .contentType(MediaType.APPLICATION_JSON).content(body("\"defaultPurchaseCost\":5")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/stock/product-deliveries").with(user(principal(UserRole.EMPLOYEE)))
                .contentType(MediaType.APPLICATION_JSON).content(stockBody(id, "1", "12.3456")))
                .andExpect(status().isForbidden());
        UUID ownBranch = branchId;
        branchId = UUID.randomUUID();
        stock(id, "1", "12.3456").andExpect(status().isNotFound());
        branchId = ownBranch;
        assertThat(movementCount(id)).isZero();
        assertThat(cost(id)).isEqualByComparingTo("12.3456");
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "0.00001", "100000000", "\"abc\""})
    void rejectsInvalidDefaultWithoutChangingSavedCost(String invalid) throws Exception {
        UUID id = create("\"defaultPurchaseCost\":12");
        edit(id, "\"defaultPurchaseCost\":" + invalid).andExpect(status().isBadRequest());
        assertThat(cost(id)).isEqualByComparingTo("12");
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "0.0001", "1.0001", "1000000000", "null", "\"abc\""})
    void rejectsInvalidQuantityWithoutDelivery(String invalid) throws Exception {
        UUID id = create("\"defaultPurchaseCost\":12");
        stock(id, invalid, "12").andExpect(status().isBadRequest());
        assertThat(movementCount(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_delivery WHERE branch_id = ?", Integer.class, branchId)).isZero();
    }

    private String body(String fields) { return "{\"name\":\"Cost product\",\"sellingPrice\":90" + (fields.isEmpty() ? "" : "," + fields) + "}"; }
    private UUID create(String fields) throws Exception {
        String response = mvc.perform(post("/api/v1/products").with(user(principal(UserRole.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON).content(body(fields))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("data").get("id").asText());
    }
    private ResultActions edit(UUID id, String fields) throws Exception {
        return mvc.perform(put("/api/v1/products/{id}", id).with(user(principal(UserRole.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON).content(body(fields)));
    }
    private String stockBody(UUID id, String quantity, String cost) {
        return "{\"productId\":\"" + id + "\",\"quantity\":" + quantity + ",\"expectedDefaultPurchaseCost\":" + cost + "}";
    }
    private ResultActions stock(UUID id, String quantity, String cost) throws Exception {
        return mvc.perform(post("/api/v1/stock/product-deliveries").with(user(principal(UserRole.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON).content(stockBody(id, quantity, cost)));
    }
    private BigDecimal cost(UUID id) { return jdbc.queryForObject("SELECT default_purchase_cost FROM product WHERE id = ?", BigDecimal.class, id); }
    private int movementCount(UUID id) { return jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE product_id = ?", Integer.class, id); }
    private void assertProduct(UUID id, String qty, String avg) throws Exception {
        mvc.perform(get("/api/v1/products").with(user(principal(UserRole.ADMIN))))
                .andExpect(jsonPath("$.data[0].qtyOnHand").value(Double.parseDouble(qty)))
                .andExpect(jsonPath("$.data[0].avgCost").value(Double.parseDouble(avg)));
    }
    private AppUserDetails principal(UserRole role) { return new AppUserDetails(actorId, branchId, "cost-owner", "unused", "Cost owner", role, true); }
}
