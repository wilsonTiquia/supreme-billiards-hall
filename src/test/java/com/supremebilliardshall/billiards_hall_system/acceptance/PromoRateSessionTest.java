package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import jakarta.persistence.EntityManager;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Happy hour: PHP 150/hour on a PHP 240/hour table, for whoever walks in.
//
// A PRICING MODE, not a kind of customer. So the cases here assert two things at once wherever
// they can -- what was charged, and that the customer type is untouched. The alternative this
// replaces was inventing a "Promo" customer type, which files a happy-hour walk-in as customer
// type Promo and destroys the record of who they actually were; a test that only checked the
// money would pass just as well against that.
//
// The reporting cases assert figures rather than absence, deliberately. The loss CTEs multiply
// by a rate, and in SQL a NULL row is skipped by sum() rather than raising: the failure mode is
// a quiet under-count, not an error, so "the query returned something" proves nothing.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PromoRateSessionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

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
    private SessionSegmentRepository sessionSegmentRepository;

    @Autowired
    private TableSessionRepository tableSessionRepository;

    private UUID branchId;
    private UUID userId;
    private UUID tableId;
    private UUID secondTableId;
    private UUID thirdTableId;
    private UUID fourthTableId;
    private UUID regularTypeId;
    private UUID friendTypeId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("PROMO");
        branch.setName("Promo Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername("promo-tester-" + UUID.randomUUID());
        user.setPasswordHash("unused");
        user.setFullName("Promo Tester");
        user.setRole(UserRole.ADMIN);
        user.setIsActive(true);
        userId = appUserRepository.saveAndFlush(user).getId();

        tableId = givenTable("Table 2", 2);
        secondTableId = givenTable("Table 3", 3);
        thirdTableId = givenTable("Table 4", 4);
        fourthTableId = givenTable("Table 5", 5);

        // Regular DISALLOWS a rate override, which is the point: the promo has to work on it
        // and the friend rate still must not.
        regularTypeId = givenCustomerType("Regular", false, true, 1);
        friendTypeId = givenCustomerType("Friend of owner", true, false, 2);
    }

    // The worked case. PHP 150/hour on a PHP 240/hour table, on a customer type that does not
    // allow a friend rate, and the customer type comes back out unchanged.
    @Test
    void aPromoRunsOnAnyCustomerTypeAndChargesTheDiscountedRate() throws Exception {
        JsonNode session = openPromo(tableId, regularTypeId, "150", "Happy hour");

        assertThat(session.get("customerTypeName").asText()).isEqualTo("Regular");
        assertThat(session.get("rateOverrideKind").asText()).isEqualTo("PROMO");
        // 150/60 = 2.5000, and both figures are kept: the per-minute one bills, the hourly one
        // is what was typed.
        assertThat(money(session, "rateOverridePerMinute")).isEqualByComparingTo("2.5000");
        assertThat(money(session, "rateOverridePerHour")).isEqualByComparingTo("150.00");
        assertThat(money(session, "standardRatePerMinute")).isEqualByComparingTo("4.0000");

        JsonNode closed = closeAfterMinutes(session, 60);
        assertThat(closed.get("billedMinutes").asInt()).isEqualTo(60);
        assertThat(money(closed, "timeAmount")).isEqualByComparingTo("150.00");
    }

    // The friend rate keeps its gate. Same rate, same table, same request shape -- only the
    // kind differs, and that is the whole difference between an event and a favour.
    @Test
    void theSameRateAsAFriendRateIsStillRefusedOnATypeThatDisallowsIt() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + regularTypeId
                                + "\",\"rateOverridePerHour\":150,\"rateOverrideKind\":\"FRIEND\"}"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("does not allow a rate override"));

        // And with no kind at all, which is what every client sent before promos existed: still
        // a friend rate, still refused, still the same message.
        mockMvc.perform(post("/api/v1/sessions")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + regularTypeId
                                + "\",\"rateOverridePerHour\":150}"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("does not allow a rate override"));
    }

    // A promo needs a reason and a friend rate does not, and that asymmetry is deliberate: the
    // whole point of the promo is measuring it, and an unlabelled one is unmeasurable.
    @Test
    void aPromoNeedsAReasonAndAFriendRateStillDoesNot() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + regularTypeId
                                + "\",\"rateOverridePerHour\":150,\"rateOverrideKind\":\"PROMO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("needs a reason"));

        JsonNode friend = body(mockMvc.perform(post("/api/v1/sessions")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + friendTypeId
                        + "\",\"rateOverridePerHour\":150}"))
                .andExpect(status().isOk())).get("data");
        assertThat(friend.get("rateOverrideKind").asText()).isEqualTo("FRIEND");
        assertThat(money(friend, "rateOverridePerMinute")).isEqualByComparingTo("2.5000");
    }

    // A kind labels an override; it does not create one. Without a rate there is nothing to
    // label, and calling a standard-rate session a promo would put a discount in the report
    // that nobody was ever given.
    @Test
    void aKindWithNoRateIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + regularTypeId
                                + "\",\"rateOverrideKind\":\"PROMO\",\"rateOverrideReason\":\"Happy hour\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("needs a rate"));
    }

    // An override written with no kind reads as FRIEND -- the same thing V18 backfilled every
    // existing row to. The backfill itself cannot be exercised through the API (the test
    // database is built from the migrations, so there are no pre-migration rows); what it can
    // check is that the default and the backfill agree, and the together-constraint is what
    // makes a missed backfill fail the migration rather than under-count a report.
    @Test
    void anOverrideWithNoKindIsAFriendRate() throws Exception {
        JsonNode session = body(mockMvc.perform(post("/api/v1/sessions")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + friendTypeId
                        + "\",\"rateOverridePerMinute\":2.5,\"rateOverrideReason\":\"Owner said so\"}"))
                .andExpect(status().isOk())).get("data");

        UUID sessionId = UUID.fromString(session.get("id").asText());
        assertThat(session.get("rateOverrideKind").asText()).isEqualTo("FRIEND");
        assertThat(asUser(() -> tableSessionRepository.findById(sessionId).orElseThrow()
                .getRateOverrideKind())).isEqualTo(RateOverrideKind.FRIEND);
    }

    // The report, which is the half the owner actually asked for.
    //
    // PHP 90.00 forgone under promos and PHP 0.00 under friend rates: (4.00 - 2.50) x 60. The
    // zero is asserted rather than assumed, because a grouping that quietly put the promo in
    // the friend bucket would leave the combined total right and the answer useless.
    @Test
    void theReportTellsAPromoFromAFavour() throws Exception {
        JsonNode promo = openPromo(tableId, regularTypeId, "150", "Happy hour");
        UUID promoBill = UUID.fromString(promo.get("billId").asText());
        closeAfterMinutes(promo, 60);
        pay(promoBill, "150.00", "promo-1");

        LocalDate businessDate = asUser(branchRepository::currentBusinessDate);
        JsonNode losses = body(mockMvc.perform(get("/api/v1/reports/daily?date=" + businessDate)
                .with(user(principal()))).andExpect(status().isOk())).get("data").get("losses");

        assertThat(losses.get("promoSessions").asInt()).isEqualTo(1);
        assertThat(money(losses, "promoForgone")).isEqualByComparingTo("90.00");
        assertThat(losses.get("friendSessions").asInt()).isZero();
        assertThat(money(losses, "friendForgone")).isEqualByComparingTo("0.00");

        // The drill-down rows sum to their own section's figure, per section.
        JsonNode detail = body(mockMvc.perform(
                get("/api/v1/reports/losses?businessDate=" + businessDate)
                        .with(user(principal()))).andExpect(status().isOk())).get("data");

        JsonNode promos = detail.get("promos");
        assertThat(promos.get("overrideSessions").asInt()).isEqualTo(1);
        assertThat(money(promos, "forgoneRevenue")).isEqualByComparingTo("90.00");
        assertThat(promos.get("lines")).hasSize(1);

        JsonNode line = promos.get("lines").get(0);
        assertThat(line.get("poolTableName").asText()).isEqualTo("Table 2");
        assertThat(line.get("rateOverrideKind").asText()).isEqualTo("PROMO");
        assertThat(line.get("billedMinutes").asInt()).isEqualTo(60);
        assertThat(money(line, "standardRatePerHour")).isEqualByComparingTo("240.00");
        assertThat(money(line, "chargedRatePerHour")).isEqualByComparingTo("150.00");
        assertThat(money(line, "forgoneRevenue")).isEqualByComparingTo("90.00");
        assertThat(line.get("reason").asText()).isEqualTo("Happy hour");

        // The friend section is empty, and empty with a zero rather than a null.
        assertThat(detail.get("friendRates").get("lines")).isEmpty();
        assertThat(money(detail.get("friendRates"), "forgoneRevenue")).isEqualByComparingTo("0.00");
    }

    // Both kinds on one night, so the split is exercised as a split rather than as two
    // one-sided days that would each pass under a query that ignored the kind entirely.
    @Test
    void aPromoAndAFriendRateOnOneNightAreReportedApart() throws Exception {
        JsonNode promo = openPromo(tableId, regularTypeId, "150", "Happy hour");
        UUID promoBill = UUID.fromString(promo.get("billId").asText());
        closeAfterMinutes(promo, 60);
        pay(promoBill, "150.00", "mixed-promo");

        // PHP 120/hour for an hour: (4.00 - 2.00) x 60 = PHP 120.00 forgone.
        JsonNode friend = openFriend(secondTableId, friendTypeId, "120");
        UUID friendBill = UUID.fromString(friend.get("billId").asText());
        closeAfterMinutes(friend, 60);
        pay(friendBill, "120.00", "mixed-friend");

        LocalDate businessDate = asUser(branchRepository::currentBusinessDate);
        JsonNode losses = body(mockMvc.perform(get("/api/v1/reports/daily?date=" + businessDate)
                .with(user(principal()))).andExpect(status().isOk())).get("data").get("losses");

        assertThat(losses.get("promoSessions").asInt()).isEqualTo(1);
        assertThat(money(losses, "promoForgone")).isEqualByComparingTo("90.00");
        assertThat(losses.get("friendSessions").asInt()).isEqualTo(1);
        assertThat(money(losses, "friendForgone")).isEqualByComparingTo("120.00");

        JsonNode detail = body(mockMvc.perform(
                get("/api/v1/reports/losses?businessDate=" + businessDate)
                        .with(user(principal()))).andExpect(status().isOk())).get("data");
        assertThat(detail.get("promos").get("lines")).hasSize(1);
        assertThat(detail.get("friendRates").get("lines")).hasSize(1);
        assertThat(detail.get("promos").get("lines").get(0).get("poolTableName").asText())
                .isEqualTo("Table 2");
        assertThat(detail.get("friendRates").get("lines").get(0).get("poolTableName").asText())
                .isEqualTo("Table 3");
    }

    /*
     * THE SPLIT MUST RECONSTRUCT THE WHOLE, not merely look plausible beside it.
     *
     * One session of each mode on one night: standard, promo, friend, flat. The four amounts
     * have to add up to totals.timeRevenue exactly, because they are that figure taken apart.
     * Two numbers on one screen that disagree about the same money are worse than one number,
     * and the owner has no way to tell which is wrong.
     */
    @Test
    void revenueByPricingModeSumsToTimeRevenue() throws Exception {
        // Standard: 60 x 4.00 = 240.00
        JsonNode standard = openStandard(tableId, regularTypeId);
        UUID standardBill = UUID.fromString(standard.get("billId").asText());
        closeAfterMinutes(standard, 60);
        pay(standardBill, "240.00", "mode-standard");

        // Promo: 60 x 2.50 = 150.00
        JsonNode promo = openPromo(secondTableId, regularTypeId, "150", "Happy hour");
        UUID promoBill = UUID.fromString(promo.get("billId").asText());
        closeAfterMinutes(promo, 60);
        pay(promoBill, "150.00", "mode-promo");

        // Friend: 60 x 2.00 = 120.00
        JsonNode friend = openFriend(thirdTableId, friendTypeId, "120");
        UUID friendBill = UUID.fromString(friend.get("billId").asText());
        closeAfterMinutes(friend, 60);
        pay(friendBill, "120.00", "mode-friend");

        // Flat: 500.00 however long it runs.
        JsonNode flat = openFlat(fourthTableId, regularTypeId, "500", "Saturday tournament");
        UUID flatBill = UUID.fromString(flat.get("billId").asText());
        closeAfterMinutes(flat, 90);
        pay(flatBill, "500.00", "mode-flat");

        LocalDate businessDate = asUser(branchRepository::currentBusinessDate);
        JsonNode report = body(mockMvc.perform(get("/api/v1/reports/daily?date=" + businessDate)
                .with(user(principal()))).andExpect(status().isOk())).get("data");

        JsonNode modes = report.get("timeRevenueByMode");
        // Four rows always, in a fixed order, zeros included -- a missing row reads as a
        // missing figure rather than as "there were none".
        assertThat(modes).hasSize(4);
        assertThat(modes.get(0).get("mode").asText()).isEqualTo("STANDARD");
        assertThat(modes.get(1).get("mode").asText()).isEqualTo("PROMO");
        assertThat(modes.get(2).get("mode").asText()).isEqualTo("FRIEND");
        assertThat(modes.get(3).get("mode").asText()).isEqualTo("FLAT");

        assertThat(money(modes.get(0), "amount")).isEqualByComparingTo("240.00");
        assertThat(money(modes.get(1), "amount")).isEqualByComparingTo("150.00");
        assertThat(money(modes.get(2), "amount")).isEqualByComparingTo("120.00");
        assertThat(money(modes.get(3), "amount")).isEqualByComparingTo("500.00");

        // The session count beside every peso figure, not only on the total.
        for (JsonNode mode : modes) {
            assertThat(mode.get("sessions").asInt()).as("sessions for %s", mode.get("mode").asText())
                    .isEqualTo(1);
        }

        BigDecimal summed = BigDecimal.ZERO;
        for (JsonNode mode : modes) {
            summed = summed.add(money(mode, "amount"));
        }
        assertThat(summed).isEqualByComparingTo(money(report.get("totals"), "timeRevenue"));
        assertThat(summed).isEqualByComparingTo("1010.00");
    }

    // A night with no promos still reports a promo row, at zero. The tile subtracts and
    // compares, so a missing row would be a missing figure rather than an absent one.
    @Test
    void aQuietNightStillReportsEveryModeAndEveryLossFigure() throws Exception {
        JsonNode standard = openStandard(tableId, regularTypeId);
        UUID billId = UUID.fromString(standard.get("billId").asText());
        closeAfterMinutes(standard, 60);
        pay(billId, "240.00", "quiet-1");

        LocalDate businessDate = asUser(branchRepository::currentBusinessDate);
        JsonNode report = body(mockMvc.perform(get("/api/v1/reports/daily?date=" + businessDate)
                .with(user(principal()))).andExpect(status().isOk())).get("data");

        assertThat(report.get("timeRevenueByMode")).hasSize(4);
        assertThat(money(report.get("timeRevenueByMode").get(1), "amount")).isEqualByComparingTo("0.00");
        assertThat(report.get("timeRevenueByMode").get(1).get("sessions").asInt()).isZero();

        // The losses object itself must still be there. The overrides CTE splits by kind with
        // aggregate FILTER rather than GROUP BY for exactly this reason: a GROUP BY would return
        // no rows on a night like this one, and the cross join would take the whole losses
        // object out of the report -- silently, on the quietest nights.
        JsonNode losses = report.get("losses");
        assertThat(losses.get("promoSessions").asInt()).isZero();
        assertThat(money(losses, "promoForgone")).isEqualByComparingTo("0.00");
        assertThat(losses.get("friendSessions").asInt()).isZero();
        assertThat(money(losses, "friendForgone")).isEqualByComparingTo("0.00");
        assertThat(losses.get("voidCount").asInt()).isZero();
    }

    // "Promo rate", not "Regular rate". The feed names an override after the customer type it
    // was given on, which is right for a favour and wrong for a promo: happy hour runs on any
    // type, so the customer-type rule alone would say the opposite of what happened.
    @Test
    void theAuditFeedNamesAPromoAfterItselfAndNotAfterTheCustomer() throws Exception {
        openPromo(tableId, regularTypeId, "150", "Happy hour");

        JsonNode entry = feedEntry("SESSION_RATE_OVERRIDE");
        assertThat(entry.get("actionLabel").asText()).isEqualTo("Promo rate");
        // The customer type is still reported, and still Regular. The label is what changed,
        // not the record of who was playing.
        assertThat(entry.get("customerTypeName").asText()).isEqualTo("Regular");
        assertThat(entry.get("subject").asText()).isEqualTo("Table 2");
        assertThat(entry.get("after").get("kind").asText()).isEqualTo("PROMO");
    }

    // The friend rate keeps the customer-type naming it has today.
    @Test
    void theAuditFeedStillNamesAFriendRateAfterTheCustomerType() throws Exception {
        openFriend(tableId, friendTypeId, "120");

        assertThat(feedEntry("SESSION_RATE_OVERRIDE").get("actionLabel").asText())
                .isEqualTo("Friend of owner rate");
    }

    // ---- helpers -------------------------------------------------------------------

    private JsonNode openPromo(UUID table, UUID customerType, String perHour, String reason) throws Exception {
        return open("{\"tableId\":\"" + table + "\",\"customerTypeId\":\"" + customerType
                + "\",\"rateOverridePerHour\":" + perHour
                + ",\"rateOverrideKind\":\"PROMO\",\"rateOverrideReason\":\"" + reason + "\"}");
    }

    private JsonNode openFriend(UUID table, UUID customerType, String perHour) throws Exception {
        return open("{\"tableId\":\"" + table + "\",\"customerTypeId\":\"" + customerType
                + "\",\"rateOverridePerHour\":" + perHour + ",\"rateOverrideKind\":\"FRIEND\"}");
    }

    private JsonNode openFlat(UUID table, UUID customerType, String amount, String reason) throws Exception {
        return open("{\"tableId\":\"" + table + "\",\"customerTypeId\":\"" + customerType
                + "\",\"flatAmount\":" + amount + ",\"flatRateReason\":\"" + reason + "\"}");
    }

    private JsonNode openStandard(UUID table, UUID customerType) throws Exception {
        return open("{\"tableId\":\"" + table + "\",\"customerTypeId\":\"" + customerType + "\"}");
    }

    private JsonNode open(String json) throws Exception {
        return body(mockMvc.perform(post("/api/v1/sessions")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())).get("data");
    }

    private JsonNode closeAfterMinutes(JsonNode session, int minutes) throws Exception {
        UUID sessionId = UUID.fromString(session.get("id").asText());
        backdate(sessionId, minutes);
        return body(mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close")
                .with(user(principal()))).andExpect(status().isOk())).get("data");
    }

    private void backdate(UUID sessionId, int minutes) {
        asUser(() -> {
            SessionSegment segment = sessionSegmentRepository.findBySessionId(sessionId).getFirst();
            segment.setStartedAt(OffsetDateTime.now().minusMinutes(minutes));
            sessionSegmentRepository.saveAndFlush(segment);
            int updated = entityManager.createNativeQuery(
                            "update table_session set opened_at = now() - make_interval(mins => :m) where id = :id")
                    .setParameter("m", minutes)
                    .setParameter("id", sessionId)
                    .executeUpdate();
            // A native update bypasses the persistence context, which would otherwise keep
            // serving the session with its original opened_at.
            entityManager.flush();
            entityManager.clear();
            return updated;
        });
    }

    private void pay(UUID billId, String amount, String key) throws Exception {
        mockMvc.perform(post("/api/v1/bills/" + billId + "/payment")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":" + amount + ",\"tendered\":" + amount
                                + ",\"idempotencyKey\":\"" + key + "\",\"billVersion\":0}"))
                .andExpect(status().isOk());
    }

    private JsonNode feedEntry(String action) throws Exception {
        JsonNode feed = body(mockMvc.perform(get("/api/v1/audit/feed?action=" + action)
                .with(user(principal()))).andExpect(status().isOk())).get("data");

        assertThat(feed.get("content")).as("a %s row in the feed", action).isNotEmpty();
        return feed.get("content").get(0);
    }

    // PHP 240/hour, which is PHP 4.00/min. Both figures are set, as the admin screen sets them,
    // so the drill-down can quote the standard in the unit it was configured in.
    private UUID givenTable(String name, int number) {
        PoolTable poolTable = new PoolTable();
        poolTable.setBranchId(branchId);
        poolTable.setName(name);
        poolTable.setTableNumber(number);
        poolTable.setIsActive(true);
        UUID id = poolTableRepository.saveAndFlush(poolTable).getId();

        PoolTableRate rate = new PoolTableRate();
        rate.setBranchId(branchId);
        rate.setPoolTableId(id);
        rate.setRatePerMinute(new BigDecimal("4.0000"));
        rate.setRatePerHour(new BigDecimal("240.00"));
        rate.setEffectiveFrom(OffsetDateTime.now().minusDays(1));
        poolTableRateRepository.saveAndFlush(rate);
        return id;
    }

    private UUID givenCustomerType(String name, boolean allowsOverride, boolean isDefault, int sortOrder) {
        CustomerType customerType = new CustomerType();
        customerType.setBranchId(branchId);
        customerType.setName(name);
        customerType.setAllowsRateOverride(allowsOverride);
        customerType.setIsDefault(isDefault);
        customerType.setSortOrder(sortOrder);
        return customerTypeRepository.saveAndFlush(customerType).getId();
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    // Money as text, never as a double: 150.00 has to stay 150.00.
    private BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private AppUserDetails principal() {
        return new AppUserDetails(userId, branchId, "promo-tester",
                "unused", "Promo Tester", UserRole.ADMIN, true);
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
