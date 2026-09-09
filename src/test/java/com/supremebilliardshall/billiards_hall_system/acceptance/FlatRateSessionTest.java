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

// Tournament pricing: PHP 500 for the table however long the session runs.
//
// The timer still runs and the minutes are still recorded; what stops growing is the charge. So
// every case here asserts BOTH -- the money that did not move and the minutes that did. A test
// that only checked the charge would pass just as well against a session whose clock had stopped.
//
// The reporting cases assert figures rather than absence, deliberately. Both loss CTEs multiply
// by a rate, and in SQL a NULL row is skipped by sum() rather than raising: the failure mode is
// a quiet under-count, not an error, so "the query returned something" proves nothing.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FlatRateSessionTest {

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
    private SessionPauseRepository sessionPauseRepository;

    @Autowired
    private ProductRepository productRepository;

    private UUID branchId;
    private UUID userId;
    private UUID tableId;
    private UUID otherTableId;
    private UUID friendTypeId;
    private UUID regularTypeId;
    private UUID beerId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("FLATRATE");
        branch.setName("Flat Rate Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername("flat-tester-" + UUID.randomUUID());
        user.setPasswordHash("unused");
        user.setFullName("Flat Tester");
        user.setRole(UserRole.ADMIN);
        user.setIsActive(true);
        userId = appUserRepository.saveAndFlush(user).getId();

        tableId = givenTable("Table 3", 3);
        otherTableId = givenTable("Table 4", 4);

        regularTypeId = givenCustomerType("Regular", false, true, 1);
        friendTypeId = givenCustomerType("Friend of owner", true, false, 2);

        beerId = givenProduct("San Miguel Pale Pilsen", "85.00");
        receiveDelivery(beerId, "48", "62.50");
    }

    // The worked case. Three hours twelve minutes on a PHP 4.00/min table costs PHP 500, not
    // PHP 768, and four beers ride on top exactly as they always did.
    @Test
    void aFlatSessionChargesTheFeeAndRecordsTheMinutes() throws Exception {
        JsonNode session = openFlat("500", "Saturday tournament");
        UUID billId = UUID.fromString(session.get("billId").asText());
        addLine(billId, beerId, "4");

        JsonNode closed = closeAfterMinutes(session, 192);

        assertThat(closed.get("billedMinutes").asInt()).isEqualTo(192);
        assertThat(money(closed, "timeAmount")).isEqualByComparingTo("500.00");
        assertThat(money(closed, "flatAmount")).isEqualByComparingTo("500.00");

        // 500 for the table, 340 for four beers at 85.
        JsonNode bill = body(mockMvc.perform(get("/api/v1/bills/" + billId).with(user(principal())))
                .andExpect(status().isOk())).get("data");
        assertThat(money(bill, "subtotalTime")).isEqualByComparingTo("500.00");
        assertThat(money(bill, "totalAmount")).isEqualByComparingTo("840.00");

        // ONE time line for the whole session, not one per segment, and it names the minutes it
        // did not charge for.
        int timeLines = 0;
        String description = null;
        for (JsonNode line : bill.get("lines")) {
            if ("TIME".equals(line.get("lineKind").asText())) {
                timeLines++;
                description = line.get("description").asText();
            }
        }
        assertThat(timeLines).isEqualTo(1);
        assertThat(description).isEqualTo("Table 3 - flat rate (192 min)");
    }

    // The charge does not tick. One minute in and five hours in, it is the same number -- which
    // is the whole feature, and the thing a per-minute bug would break silently.
    @Test
    void theChargeIsTheSameOneMinuteInAndFiveHoursIn() throws Exception {
        JsonNode session = openFlat("500", "Saturday tournament");
        UUID sessionId = UUID.fromString(session.get("id").asText());

        // The figure is right before any time has passed at all.
        assertThat(money(session, "timeAmount")).isEqualByComparingTo("500.00");

        backdate(sessionId, 1);
        JsonNode oneMinute = liveSession(sessionId);
        assertThat(oneMinute.get("billedMinutes").asInt()).isEqualTo(1);
        assertThat(money(oneMinute, "timeAmount")).isEqualByComparingTo("500.00");

        backdate(sessionId, 300);
        JsonNode fiveHours = liveSession(sessionId);
        // The clock moved, so the test is comparing the charge across real elapsed time.
        assertThat(fiveHours.get("billedMinutes").asInt()).isEqualTo(300);
        assertThat(money(fiveHours, "timeAmount")).isEqualByComparingTo("500.00");
    }

    // Pause still stops the clock. It lowers the recorded minutes and leaves the charge alone,
    // which is why the button stays available rather than being disabled.
    @Test
    void pausingLowersTheRecordedMinutesAndNotTheCharge() throws Exception {
        JsonNode session = openFlat("500", "Saturday tournament");
        UUID sessionId = UUID.fromString(session.get("id").asText());

        backdate(sessionId, 60);
        // Ten minutes wholly inside the session: minutes 20 to 30.
        asUser(() -> {
            SessionPause pause = new SessionPause();
            pause.setBranchId(branchId);
            pause.setSessionId(sessionId);
            pause.setPausedAt(OffsetDateTime.now().minusMinutes(40));
            pause.setResumedAt(OffsetDateTime.now().minusMinutes(30));
            pause.setPausedBy(userId);
            pause.setResumedBy(userId);
            return sessionPauseRepository.saveAndFlush(pause);
        });

        JsonNode closed = body(mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close")
                .with(user(principal()))).andExpect(status().isOk())).get("data");

        assertThat(closed.get("billedMinutes").asInt()).isEqualTo(50);
        assertThat(money(closed, "timeAmount")).isEqualByComparingTo("500.00");
    }

    // One session, one pricing story. A 400 and not the 409 the database constraint alone would
    // give: the request was malformed before it was sent, not in conflict with anything.
    @Test
    void aFlatRateAndAFriendRateInOneRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + friendTypeId
                                + "\",\"rateOverridePerMinute\":2,\"flatAmount\":500,"
                                + "\"flatRateReason\":\"Tournament\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("not both"));
    }

    // The reason is the whole control on a fee somebody chose, so it is not optional -- and zero
    // is allowed, which is exactly why. A comped table with nobody's name on it is the row this
    // system cannot have.
    @Test
    void aFlatRateNeedsAReasonAndZeroIsAllowedWithOne() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + regularTypeId
                                + "\",\"flatAmount\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("needs a reason"));

        JsonNode comped = openFlat("0", "Owner's table, tournament final");
        assertThat(money(closeAfterMinutes(comped, 45), "timeAmount")).isEqualByComparingTo("0.00");
    }

    // "Charge fewer minutes" has no meaning against a fee that was never per-minute. Refused
    // rather than silently scaled: the arithmetic behind it multiplies the zero rate a flat
    // session's segments carry, which would quietly zero the fee.
    @Test
    void reducingTheTimeOnAFlatSessionIsRefused() throws Exception {
        JsonNode session = openFlat("500", "Saturday tournament");
        UUID sessionId = UUID.fromString(session.get("id").asText());
        closeAfterMinutes(session, 192);

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/billed-minutes")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"billedMinutes\":60,\"reason\":\"Regular customer\"}"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("flat rate"));
    }

    // The whole report in one place, because these figures are only trustworthy together.
    //
    // Note what is asserted about the FRIEND-RATE figure: exactly zero. The overrides CTE finds
    // giveaways with "rate_override_per_minute IS NOT NULL", and it is
    // table_session_flat_xor_override_chk that guarantees a flat session is never also counted
    // there. If that constraint went, this is the assertion that would catch it.
    @Test
    void theDailyReportSeparatesTheFlatGiveawayFromEveryOtherFigure() throws Exception {
        JsonNode session = openFlat("500", "Saturday tournament");
        UUID billId = UUID.fromString(session.get("billId").asText());
        addLine(billId, beerId, "4");
        closeAfterMinutes(session, 192);
        pay(billId, "840.00", "flat-1");

        LocalDate businessDate = asUser(branchRepository::currentBusinessDate);
        JsonNode report = body(mockMvc.perform(get("/api/v1/reports/daily?date=" + businessDate)
                .with(user(principal()))).andExpect(status().isOk())).get("data");

        assertThat(money(report.get("totals"), "gross")).isEqualByComparingTo("840.00");
        // subtotal_time is summed from the TIME lines at checkout, so the flat line flows
        // through with no reporting change at all.
        assertThat(money(report.get("totals"), "timeRevenue")).isEqualByComparingTo("500.00");
        assertThat(money(report.get("totals"), "itemRevenue")).isEqualByComparingTo("340.00");

        // 192 x 4.00 = 768 metered, less the 500 charged.
        JsonNode losses = report.get("losses");
        assertThat(losses.get("flatSessions").asInt()).isEqualTo(1);
        assertThat(money(losses, "flatForgone")).isEqualByComparingTo("268.00");
        assertThat(losses.get("friendSessions").asInt()).isZero();
        assertThat(money(losses, "friendForgone")).isEqualByComparingTo("0.00");
        assertThat(losses.get("reducedSessions").asInt()).isZero();

        // The drill-down rows must sum to the tile, or one of them is lying.
        JsonNode flatRates = body(mockMvc.perform(
                get("/api/v1/reports/losses?businessDate=" + businessDate)
                        .with(user(principal()))).andExpect(status().isOk()))
                .get("data").get("flatRates");
        assertThat(flatRates.get("lines")).hasSize(1);
        assertThat(money(flatRates, "flatForgone")).isEqualByComparingTo("268.00");

        JsonNode line = flatRates.get("lines").get(0);
        assertThat(line.get("poolTableName").asText()).isEqualTo("Table 3");
        assertThat(line.get("billedMinutes").asInt()).isEqualTo(192);
        assertThat(money(line, "meteredRevenue")).isEqualByComparingTo("768.00");
        assertThat(money(line, "flatAmount")).isEqualByComparingTo("500.00");
        assertThat(money(line, "forgoneRevenue")).isEqualByComparingTo("268.00");
        assertThat(line.get("reason").asText()).isEqualTo("Saturday tournament");
    }

    // Utilisation, on its OWN session with no pause.
    //
    // Deliberately separate from the pause case above: occupiedMinutes is raw wall clock and
    // does NOT deduct pauses -- that is what the field name says and what the segment_minutes
    // CTE explains -- so a session that both paused and asserted utilisation would either fail
    // for the right reason or pass for the wrong one, with no way to tell which from the test
    // name. Two facts, two sessions.
    @Test
    void utilisationCountsTheMinutesAFlatSessionPlayed() throws Exception {
        JsonNode session = openFlat("500", "Saturday tournament");
        UUID billId = UUID.fromString(session.get("billId").asText());
        closeAfterMinutes(session, 192);
        pay(billId, "500.00", "flat-util-1");

        LocalDate businessDate = asUser(branchRepository::currentBusinessDate);
        JsonNode report = body(mockMvc.perform(get("/api/v1/reports/daily?date=" + businessDate)
                .with(user(principal()))).andExpect(status().isOk())).get("data");

        Integer minutes = null;
        for (JsonNode row : report.get("tableUtilisation")) {
            if ("Table 3".equals(row.get("tableName").asText())) {
                minutes = row.get("occupiedMinutes").asInt();
            }
        }
        // The rate is zero on the segments; utilisation reads time, not money, and is unaffected.
        assertThat(minutes).isEqualTo(192);
    }

    // A fee ABOVE the metered figure is not a loss, and must not net off against a real one.
    // Clamped per row, not on the sum -- which is the difference between reporting PHP 268.00
    // and reporting PHP 68.00 while a genuine giveaway hides behind someone's good night.
    @Test
    void aFlatFeeAboveTheMeteredFigureContributesNothing() throws Exception {
        JsonNode giveaway = openFlat("500", "Saturday tournament");
        UUID giveawayBill = UUID.fromString(giveaway.get("billId").asText());
        closeAfterMinutes(giveaway, 192);
        pay(giveawayBill, "500.00", "flat-a");

        // PHP 700 for 30 minutes, which the meter would have priced at PHP 120.
        JsonNode premium = openFlatOn(otherTableId, "700", "Private booking");
        UUID premiumBill = UUID.fromString(premium.get("billId").asText());
        closeAfterMinutes(premium, 30);
        pay(premiumBill, "700.00", "flat-b");

        LocalDate businessDate = asUser(branchRepository::currentBusinessDate);
        JsonNode losses = body(mockMvc.perform(get("/api/v1/reports/daily?date=" + businessDate)
                .with(user(principal()))).andExpect(status().isOk())).get("data").get("losses");

        assertThat(losses.get("flatSessions").asInt()).isEqualTo(2);
        // 268 from the giveaway and 0 -- not -580 -- from the premium booking.
        assertThat(money(losses, "flatForgone")).isEqualByComparingTo("268.00");
    }

    // ---- helpers -------------------------------------------------------------------

    private JsonNode openFlat(String amount, String reason) throws Exception {
        return openFlatOn(tableId, amount, reason);
    }

    private JsonNode openFlatOn(UUID table, String amount, String reason) throws Exception {
        return body(mockMvc.perform(post("/api/v1/sessions")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableId\":\"" + table + "\",\"customerTypeId\":\"" + regularTypeId
                        + "\",\"flatAmount\":" + amount + ",\"flatRateReason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())).get("data");
    }

    private JsonNode liveSession(UUID sessionId) throws Exception {
        return body(mockMvc.perform(get("/api/v1/sessions/" + sessionId).with(user(principal())))
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

    private void addLine(UUID billId, UUID productId, String quantity) throws Exception {
        mockMvc.perform(post("/api/v1/bills/" + billId + "/lines")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk());
    }

    private void pay(UUID billId, String amount, String key) throws Exception {
        mockMvc.perform(post("/api/v1/bills/" + billId + "/payment")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":" + amount + ",\"tendered\":" + amount
                                + ",\"idempotencyKey\":\"" + key + "\",\"billVersion\":0}"))
                .andExpect(status().isOk());
    }

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

    private UUID givenProduct(String name, String sellingPrice) {
        Product product = new Product();
        product.setBranchId(branchId);
        product.setName(name);
        product.setSellingPrice(new BigDecimal(sellingPrice));
        product.setAvgCost(BigDecimal.ZERO);
        product.setQtyOnHand(BigDecimal.ZERO);
        product.setIsActive(true);
        return productRepository.saveAndFlush(product).getId();
    }

    private void receiveDelivery(UUID productId, String quantity, String unitCost) {
        try {
            mockMvc.perform(post("/api/v1/stock/deliveries")
                            .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lines\":[{\"productId\":\"" + productId + "\",\"quantity\":" + quantity
                                    + ",\"unitCost\":" + unitCost + "}]}"))
                    .andExpect(status().isOk());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    // Money as text, never as a double: 500.00 has to stay 500.00.
    private BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private AppUserDetails principal() {
        return new AppUserDetails(userId, branchId, "flat-tester",
                "unused", "Flat Tester", UserRole.ADMIN, true);
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
