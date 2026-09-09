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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The audit feed called every rate override a "friend rate given", because the vocabulary maps
// one constant to one phrase and the owner now has customer types beyond friends. It reads the
// customer type off the session instead.
//
// A JOIN rather than a wider snapshot, and that choice is what these tests protect: audit_log is
// append-only, so rows already written could never be backfilled, while a join names them
// correctly too. Nothing else here would notice if that regressed into a snapshot -- the new
// rows would look right and the history would silently keep the old wording.
//
// The feed's UNION ALL is the other thing guarded here. Its two branches have to agree on the
// count and type of every column, and Postgres rejects a mismatch when the statement is
// prepared -- so a wrong column breaks the WHOLE feed, not one row. Both branches are exercised.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditFeedLabelTest {

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
    private TableSessionRepository tableSessionRepository;

    @Autowired
    private ProductRepository productRepository;

    private UUID branchId;
    private UUID userId;
    private UUID tableId;
    private UUID happyHourId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("AUDITLBL");
        branch.setName("Audit Label Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername("audit-tester-" + UUID.randomUUID());
        user.setPasswordHash("unused");
        user.setFullName("Audit Tester");
        user.setRole(UserRole.ADMIN);
        user.setIsActive(true);
        userId = appUserRepository.saveAndFlush(user).getId();

        PoolTable poolTable = new PoolTable();
        poolTable.setBranchId(branchId);
        poolTable.setName("Table 2");
        poolTable.setTableNumber(2);
        poolTable.setIsActive(true);
        tableId = poolTableRepository.saveAndFlush(poolTable).getId();

        PoolTableRate rate = new PoolTableRate();
        rate.setBranchId(branchId);
        rate.setPoolTableId(tableId);
        rate.setRatePerMinute(new BigDecimal("4.0000"));
        rate.setRatePerHour(new BigDecimal("240.00"));
        rate.setEffectiveFrom(OffsetDateTime.now().minusDays(1));
        poolTableRateRepository.saveAndFlush(rate);

        CustomerType happyHour = new CustomerType();
        happyHour.setBranchId(branchId);
        happyHour.setName("Happy Hour");
        happyHour.setAllowsRateOverride(true);
        happyHour.setIsDefault(true);
        happyHour.setSortOrder(1);
        happyHourId = customerTypeRepository.saveAndFlush(happyHour).getId();
    }

    // "Happy Hour rate", not "Friend rate given". The subject is still the table.
    @Test
    void aRateOverrideIsNamedAfterTheCustomerTypeItWasGivenOn() throws Exception {
        openSessionWithOverride();

        JsonNode entry = feedEntry("SESSION_RATE_OVERRIDE");
        assertThat(entry.get("actionLabel").asText()).isEqualTo("Happy Hour rate");
        assertThat(entry.get("customerTypeName").asText()).isEqualTo("Happy Hour");
        assertThat(entry.get("entityLabel").asText()).isEqualTo("Table");
        assertThat(entry.get("subject").asText()).isEqualTo("Table 2");
    }

    // The join reads the session as it stands, so a type renamed after the fact renames the
    // history with it. This is the half a snapshot could never do: audit_log is append-only and
    // the row written at open time can never be revisited.
    @Test
    void renamingTheCustomerTypeRenamesTheHistory() throws Exception {
        openSessionWithOverride();

        asUser(() -> {
            CustomerType type = customerTypeRepository.findById(happyHourId).orElseThrow();
            type.setName("Student Rate");
            return customerTypeRepository.saveAndFlush(type);
        });

        assertThat(feedEntry("SESSION_RATE_OVERRIDE").get("actionLabel").asText())
                .isEqualTo("Student Rate rate");
    }

    // table_session.customer_type_id is NULLABLE, so this is a reachable path rather than a
    // defensive one, and it has to fall back to wording that is true of every customer type.
    @Test
    void aSessionWithNoCustomerTypeFallsBackToTheVocabulary() throws Exception {
        UUID sessionId = openSessionWithOverride();

        asUser(() -> {
            TableSession session = tableSessionRepository.findById(sessionId).orElseThrow();
            session.setCustomerTypeId(null);
            return tableSessionRepository.saveAndFlush(session);
        });

        JsonNode entry = feedEntry("SESSION_RATE_OVERRIDE");
        assertThat(entry.get("actionLabel").asText()).isEqualTo("Rate overridden");
        assertThat(entry.get("customerTypeName").isNull()).isTrue();
    }

    // Only the rate override is renamed. A session carrying a customer type sees other actions
    // too, and "Happy Hour rate" would be a lie on a row that changed no rate.
    @Test
    void otherActionsOnTheSameSessionKeepTheirOwnWording() throws Exception {
        openSessionWithOverride();

        // POOL_TABLE_RATE_CHANGED is on the table, not the session, so it carries no customer
        // type at all — and must still read as itself.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/tables/" + tableId + "/rate")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ratePerHour\":300}"))
                .andExpect(status().isOk());

        JsonNode entry = feedEntry("POOL_TABLE_RATE_CHANGED");
        assertThat(entry.get("actionLabel").asText()).isEqualTo("Table rate changed");
        assertThat(entry.get("customerTypeName").isNull()).isTrue();
    }

    // The stock half of the UNION. It has no session and so no customer type, and the column has
    // to come back typed and null rather than breaking the query for every other row.
    @Test
    void theStockHalfOfTheFeedCarriesNoCustomerType() throws Exception {
        Product product = new Product();
        product.setBranchId(branchId);
        product.setName("San Miguel Pale Pilsen");
        product.setSellingPrice(new BigDecimal("90.00"));
        product.setAvgCost(BigDecimal.ZERO);
        product.setQtyOnHand(BigDecimal.ZERO);
        product.setIsActive(true);
        UUID productId = productRepository.saveAndFlush(product).getId();

        mockMvc.perform(post("/api/v1/stock/deliveries")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"productId\":\"" + productId
                                + "\",\"quantity\":24,\"unitCost\":62.50}]}"))
                .andExpect(status().isOk());

        JsonNode entry = feedEntry("STOCK_DELIVERY");
        assertThat(entry.get("source").asText()).isEqualTo("STOCK");
        assertThat(entry.get("customerTypeName").isNull()).isTrue();
        assertThat(entry.get("actionLabel").asText()).isEqualTo("Delivery received");
    }

    // The filter dropdown gets the vocabulary wording, with no session in scope to name — which
    // is the other reason the fallback has to read correctly for every customer type.
    @Test
    void theFilterOptionUsesTheVocabularyWording() throws Exception {
        openSessionWithOverride();

        JsonNode options = body(mockMvc.perform(get("/api/v1/audit/filters")
                .with(user(principal()))).andExpect(status().isOk())).get("data").get("actions");

        String label = null;
        for (JsonNode option : options) {
            if ("SESSION_RATE_OVERRIDE".equals(option.get("action").asText())) {
                label = option.get("label").asText();
            }
        }
        assertThat(label).isEqualTo("Rate overridden");
    }

    /*
     * WHICH BILL. Every row pointing at a bill came back with subject: null.
     *
     * Four actions land on the bill entity -- BILL_DISCOUNTED, VOUCHER_REDEEMED,
     * BILL_LEFT_UNPAID, BILL_CLOSED_NO_CHARGE -- and the feed had no join for any of them, so
     * the Audit screen could say a discount had been given and not which bill it came off. A
     * discount is money any employee can give away on their own, which makes this the row that
     * most has to identify itself.
     *
     * Both halves are asserted in one test on purpose: the point is not that a closed bill
     * shows its receipt number, it is that THE SAME ROW changes from one to the other without
     * being rewritten. audit_log is append-only, so a number snapshotted at write time would
     * have been null for ever on every discount ever taken -- the receipt is allocated at
     * checkout, and a discount is always agreed before that.
     */
    @Test
    void aBillRowIsNamedByItsReceiptNumberOnceItHasOne() throws Exception {
        UUID productId = givenProduct();
        JsonNode session = body(mockMvc.perform(post("/api/v1/sessions")
                .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + happyHourId + "\"}"))
                .andExpect(status().isOk())).get("data");
        UUID sessionId = UUID.fromString(session.get("id").asText());
        UUID billId = UUID.fromString(session.get("billId").asText());

        mockMvc.perform(post("/api/v1/bills/" + billId + "/lines")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close").with(user(principal())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/bills/" + billId + "/discount")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargeAmount\":60.00,\"reason\":\"Regular, owner said yes\"}"))
                .andExpect(status().isOk());

        // Still OPEN, so there is no receipt number yet: the table and the time carry the row.
        JsonNode beforeCheckout = feedEntry("BILL_DISCOUNTED");
        assertThat(beforeCheckout.get("entityLabel").asText()).isEqualTo("Bill");
        assertThat(beforeCheckout.get("subject").asText()).matches("Table 2 · \\d{2}:\\d{2}");

        JsonNode bill = body(mockMvc.perform(get("/api/v1/bills/" + billId).with(user(principal())))
                .andExpect(status().isOk())).get("data");
        mockMvc.perform(post("/api/v1/bills/" + billId + "/payment")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":60.00,\"tendered\":60.00,"
                                + "\"idempotencyKey\":\"audit-subject-1\",\"billVersion\":"
                                + bill.get("version").asInt() + "}"))
                .andExpect(status().isOk());

        // The same row, re-read. Resolved at read time, so the number arrives on history too.
        assertThat(feedEntry("BILL_DISCOUNTED").get("subject").asText()).isEqualTo("#1");
    }

    private UUID givenProduct() {
        Product product = new Product();
        product.setBranchId(branchId);
        product.setName("San Miguel Pale Pilsen");
        product.setSellingPrice(new BigDecimal("90.00"));
        product.setAvgCost(BigDecimal.ZERO);
        product.setQtyOnHand(new BigDecimal("24"));
        product.setIsActive(true);
        return asUser(() -> productRepository.saveAndFlush(product).getId());
    }

    private UUID openSessionWithOverride() throws Exception {
        JsonNode session = body(mockMvc.perform(post("/api/v1/sessions")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + happyHourId
                        + "\",\"rateOverridePerHour\":100,\"rateOverrideReason\":\"Happy hour\"}"))
                .andExpect(status().isOk())).get("data");
        return UUID.fromString(session.get("id").asText());
    }

    private JsonNode feedEntry(String action) throws Exception {
        JsonNode feed = body(mockMvc.perform(get("/api/v1/audit/feed?action=" + action)
                .with(user(principal()))).andExpect(status().isOk())).get("data");

        assertThat(feed.get("content")).as("a %s row in the feed", action).isNotEmpty();
        return feed.get("content").get(0);
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private AppUserDetails principal() {
        return new AppUserDetails(userId, branchId, "audit-tester",
                "unused", "Audit Tester", UserRole.ADMIN, true);
    }

    private <T> T asUser(Supplier<T> work) {
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
