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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The friend rate in pesos per hour. The same input convenience TableRateHourlyTest covers for
// the table rate, and guarded the same way: what is asserted is the MONEY, because the way this
// feature goes wrong is by quietly becoming a second source of price.
//
// The forgone-revenue cases are the reason this file drives the real endpoints rather than the
// service. LOSSES_DETAIL_SQL has no coverage at all today — nothing else in the suite calls
// /reports/losses — and while CheckoutAcceptanceTest does drive /reports/daily, its trace bill
// carries no friend rate, so the `overrides` CTE is only ever exercised with an empty result.
// That guards the statement's SYNTAX (one broken CTE fails the whole query) but says nothing
// about whether the arithmetic means what it should. These tests are that second half.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FriendRateHourlyTest {

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
    private SessionSegmentRepository sessionSegmentRepository;

    private UUID branchId;
    private UUID userId;
    private UUID tableId;
    private UUID friendTypeId;
    private UUID regularTypeId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("FRIEND");
        branch.setName("Friend Rate Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername("friend-tester-" + UUID.randomUUID());
        user.setPasswordHash("unused");
        user.setFullName("Friend Tester");
        user.setRole(UserRole.ADMIN);
        user.setIsActive(true);
        userId = appUserRepository.saveAndFlush(user).getId();

        PoolTable poolTable = new PoolTable();
        poolTable.setBranchId(branchId);
        poolTable.setName("Table 3");
        poolTable.setTableNumber(3);
        poolTable.setIsActive(true);
        tableId = poolTableRepository.saveAndFlush(poolTable).getId();

        // Configured hourly: PHP 240/hour, which is PHP 4.0000 a minute.
        PoolTableRate rate = new PoolTableRate();
        rate.setBranchId(branchId);
        rate.setPoolTableId(tableId);
        rate.setRatePerMinute(new BigDecimal("4.0000"));
        rate.setRatePerHour(new BigDecimal("240.00"));
        rate.setEffectiveFrom(OffsetDateTime.now().minusDays(1));
        poolTableRateRepository.saveAndFlush(rate);

        friendTypeId = givenCustomerType("Friend of owner", true, false, 2);
        regularTypeId = givenCustomerType("Regular", false, true, 1);
    }

    // The headline case. Half price, typed the way the owner says it out loud.
    @Test
    void anHourlyFriendRateHalvesTheRateAndBillsFromTheDerivedFigure() throws Exception {
        JsonNode session = openSession("\"rateOverridePerHour\":120,\"rateOverrideReason\":\"Owner's cousin\"");

        assertThat(money(session, "rateOverridePerMinute")).isEqualByComparingTo("2.0000");
        assertThat(money(session, "rateOverridePerHour")).isEqualByComparingTo("120.00");
        // The standard is snapshotted in both units, so the drill-down can compare like with like.
        assertThat(money(session, "standardRatePerMinute")).isEqualByComparingTo("4.0000");
        assertThat(money(session, "standardRatePerHour")).isEqualByComparingTo("240.00");

        assertThat(closeAfterMinutes(session, 45)).isEqualByComparingTo("90.00");
    }

    // The giveaway has to reach the report, in both the tile figure and the rows behind it.
    // PHP 90.00 charged against PHP 180.00 standard for the same 45 minutes.
    @Test
    void theGiveawayReachesForgoneRevenueAndTheDrillDownQuotesItHourly() throws Exception {
        JsonNode session = openSession("\"rateOverridePerHour\":120,\"rateOverrideReason\":\"Owner's cousin\"");
        assertThat(closeAfterMinutes(session, 45)).isEqualByComparingTo("90.00");

        LocalDate businessDate = asUser(branchRepository::currentBusinessDate);

        JsonNode daily = body(mockMvc.perform(get("/api/v1/reports/daily?date=" + businessDate)
                .with(user(principal()))).andExpect(status().isOk())).get("data");
        assertThat(daily.get("losses").get("friendSessions").asInt()).isEqualTo(1);
        assertThat(money(daily.get("losses"), "friendForgone")).isEqualByComparingTo("90.00");

        JsonNode friendRates = body(mockMvc.perform(
                get("/api/v1/reports/losses?businessDate=" + businessDate)
                        .with(user(principal()))).andExpect(status().isOk()))
                .get("data").get("friendRates");

        // The section total and the tile are computed from the same rows and must agree.
        assertThat(money(friendRates, "forgoneRevenue")).isEqualByComparingTo("90.00");
        assertThat(friendRates.get("lines")).hasSize(1);

        JsonNode line = friendRates.get("lines").get(0);
        assertThat(line.get("poolTableName").asText()).isEqualTo("Table 3");
        assertThat(line.get("billedMinutes").asInt()).isEqualTo(45);
        assertThat(money(line, "forgoneRevenue")).isEqualByComparingTo("90.00");
        // The arithmetic stays per-minute; the hourly pair rides alongside for the reader.
        assertThat(money(line, "standardRatePerMinute")).isEqualByComparingTo("4.0000");
        assertThat(money(line, "chargedRatePerMinute")).isEqualByComparingTo("2.0000");
        assertThat(money(line, "standardRatePerHour")).isEqualByComparingTo("240.00");
        assertThat(money(line, "chargedRatePerHour")).isEqualByComparingTo("120.00");
    }

    // A comped game. Zero is a legitimate friend rate and the reason the hourly check is
    // ">= 0" rather than the "> 0" the table rate uses — copying that constraint across would
    // have rejected exactly the giveaway the feature exists to record.
    @Test
    void aZeroFriendRateWorksTypedEitherWay() throws Exception {
        JsonNode hourly = openSession("\"rateOverridePerHour\":0,\"rateOverrideReason\":\"Comped\"");
        assertThat(money(hourly, "rateOverridePerMinute")).isEqualByComparingTo("0.0000");
        assertThat(money(hourly, "rateOverridePerHour")).isEqualByComparingTo("0.00");
        assertThat(closeAfterMinutes(hourly, 45)).isEqualByComparingTo("0.00");

        JsonNode perMinute = openSession("\"rateOverridePerMinute\":0,\"rateOverrideReason\":\"Comped\"");
        assertThat(money(perMinute, "rateOverridePerMinute")).isEqualByComparingTo("0.0000");
        assertThat(perMinute.get("rateOverridePerHour").isNull()).isTrue();
        assertThat(closeAfterMinutes(perMinute, 45)).isEqualByComparingTo("0.00");
    }

    // AT MOST one, unlike the table rate where a rate is mandatory. Neither is the ordinary
    // case and must keep working: it means charge the standard rate.
    @Test
    void bothOverridesIsRejectedAndNeitherChargesTheStandardRate() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + friendTypeId
                                + "\",\"rateOverridePerMinute\":2,\"rateOverridePerHour\":120}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("not both"));

        JsonNode session = openSession(null);
        assertThat(session.get("rateOverridePerMinute").isNull()).isTrue();
        assertThat(session.get("rateOverridePerHour").isNull()).isTrue();
        // 45 minutes at the table's own 4.0000.
        assertThat(closeAfterMinutes(session, 45)).isEqualByComparingTo("180.00");
    }

    // The customer-type gate tests the DERIVED rate, so it covers both input modes without
    // knowing about either. This proves the hourly form did not slip past it.
    @Test
    void anHourlyOverrideIsStillRejectedForACustomerTypeThatDisallowsIt() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + regularTypeId
                                + "\",\"rateOverridePerHour\":120}"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("does not allow a rate override"));
    }

    // Re-rating the table must not reprice a session already running on it. The segment holds
    // the derived per-minute figure, snapshotted at open, and that is what bills.
    @Test
    void anOpenSessionKeepsItsSnapshottedRateWhenTheTableIsReRated() throws Exception {
        JsonNode session = openSession("\"rateOverridePerHour\":120,\"rateOverrideReason\":\"Owner's cousin\"");

        mockMvc.perform(put("/api/v1/tables/" + tableId + "/rate")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ratePerHour\":600}"))
                .andExpect(status().isOk());

        // Still the 2.0000 it opened at, not half of the table's new 10.0000.
        assertThat(closeAfterMinutes(session, 45)).isEqualByComparingTo("90.00");
    }

    private JsonNode openSession(String overrideJson) throws Exception {
        String body = "{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\""
                + (overrideJson == null ? regularTypeId : friendTypeId) + "\""
                + (overrideJson == null ? "" : "," + overrideJson) + "}";
        return body(mockMvc.perform(post("/api/v1/sessions")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk())).get("data");
    }

    // Moves the segment back and closes, so a session of any length costs the test nothing.
    private BigDecimal closeAfterMinutes(JsonNode session, int minutes) throws Exception {
        UUID sessionId = UUID.fromString(session.get("id").asText());

        asUser(() -> {
            SessionSegment segment = sessionSegmentRepository.findBySessionId(sessionId).getFirst();
            segment.setStartedAt(OffsetDateTime.now().minusMinutes(minutes));
            return sessionSegmentRepository.saveAndFlush(segment);
        });

        JsonNode closed = body(mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close")
                .with(user(principal()))).andExpect(status().isOk())).get("data");
        assertThat(closed.get("billedMinutes").asInt()).isEqualTo(minutes);
        return money(closed, "timeAmount");
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

    // Read money as text, never as a double: 2.0000 has to stay 2.0000.
    private BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private AppUserDetails principal() {
        return new AppUserDetails(userId, branchId, "friend-tester",
                "unused", "Friend Tester", UserRole.ADMIN, true);
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
