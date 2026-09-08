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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The owner configures tables in pesos per hour and kept mistyping the division. The hourly
// input mode does that division once, on the server.
//
// It is an INPUT CONVENIENCE and nothing else, which is exactly what needs guarding: the way
// this feature goes wrong is by quietly becoming a second source of price. So every case here
// asserts the money as well as the shape — PHP 240/hour is 4.0000/min and a 45 minute session
// still costs PHP 180.00, computed from the per-minute rate the way it always was.
//
// The PHP 200/hour cases are the honest ones. That rate does not divide by 60, and the tests
// pin what the system actually does with the remainder rather than what the arithmetic looks
// like it should do.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TableRateHourlyTest {

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
    private UUID customerTypeId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("RATETEST");
        branch.setName("Rate Test Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername("rate-tester-" + UUID.randomUUID());
        user.setPasswordHash("unused");
        user.setFullName("Rate Tester");
        user.setRole(UserRole.ADMIN);
        user.setIsActive(true);
        userId = appUserRepository.saveAndFlush(user).getId();

        PoolTable poolTable = new PoolTable();
        poolTable.setBranchId(branchId);
        poolTable.setName("Table 3");
        poolTable.setTableNumber(3);
        poolTable.setIsActive(true);
        tableId = poolTableRepository.saveAndFlush(poolTable).getId();

        // Configured per minute, the way every table predating the hourly mode is.
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
    }

    // The headline case, and the one the owner will try first. 240 divides by 60 exactly, so
    // there is nothing to be honest about: what was typed and what an hour costs are the same
    // figure, and the money is the money it always was.
    @Test
    void twoHundredAndFortyAnHourIsFourPesosAMinuteAndBillsLikeIt() throws Exception {
        JsonNode table = changeRate("{\"ratePerHour\":240}").get("data");

        assertThat(money(table, "ratePerMinute")).isEqualByComparingTo("4.0000");
        assertThat(money(table, "ratePerHour")).isEqualByComparingTo("240.00");
        assertThat(money(table, "effectiveRatePerHour")).isEqualByComparingTo("240.0000");

        assertThat(closeAfterMinutes(45)).isEqualByComparingTo("180.00");
    }

    // The rate that does not divide. 200/60 is 3.3333 at four decimals, which prices an hour
    // at 199.9980 rather than 200 — and the response says so rather than presenting 200 as
    // though it were exact.
    //
    // A full hour nonetheless bills 200.00: the line rounds to centavos, and the derivation
    // can never be more than 0.003 out over sixty minutes. The shortfall only survives the
    // rounding over a longer session, which is why the three hour case is here too.
    @Test
    void twoHundredAnHourStoresTheRoundedRateAndReportsTheGap() throws Exception {
        JsonNode table = changeRate("{\"ratePerHour\":200}").get("data");

        assertThat(money(table, "ratePerMinute")).isEqualByComparingTo("3.3333");
        assertThat(money(table, "ratePerHour")).isEqualByComparingTo("200.00");
        assertThat(money(table, "effectiveRatePerHour")).isEqualByComparingTo("199.9980");

        assertThat(closeAfterMinutes(60)).isEqualByComparingTo("200.00");
    }

    // Three hours at 3.3333 is 599.994, which floors to 599.99 against the 600.00 that
    // "PHP 200 an hour" implies. One centavo, in the customer's favour, and pinned here so
    // nobody later "fixes" it by rounding the rate up or by billing from the hourly figure.
    @Test
    void theShortfallSurvivesRoundingOverALongSession() throws Exception {
        changeRate("{\"ratePerHour\":200}");

        assertThat(closeAfterMinutes(180)).isEqualByComparingTo("599.99");
    }

    // The receipt has to print the rate that billed. It used to round the line description to
    // two decimals, which was invisible while every rate was 4.0000 and becomes "180 min @
    // 3.33/min" against a charge of 599.99 the moment an hourly rate exists.
    @Test
    void theTimeLineNamesTheRateAtThePrecisionItBilledAt() throws Exception {
        changeRate("{\"ratePerHour\":200}");
        UUID sessionId = openBackdatedSession(180);

        JsonNode closed = body(mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close")
                .with(user(principal()))).andExpect(status().isOk())).get("data");

        JsonNode bill = body(mockMvc.perform(get("/api/v1/bills/" + closed.get("billId").asText())
                .with(user(principal()))).andExpect(status().isOk())).get("data");

        String description = null;
        for (JsonNode line : bill.get("lines")) {
            if ("TIME".equals(line.get("lineKind").asText())) description = line.get("description").asText();
        }
        assertThat(description).isEqualTo("Table 3 - 180 min @ 3.3333/min");
    }

    // A table configured per minute is untouched: no hourly figure is invented for it, because
    // multiplying one up would put a number on the screen the admin never typed.
    @Test
    void aTableConfiguredPerMinuteHasNoHourlyFigure() throws Exception {
        JsonNode table = onlyTable();

        assertThat(money(table, "ratePerMinute")).isEqualByComparingTo("4.0000");
        assertThat(table.get("ratePerHour").isNull()).isTrue();
    }

    // Both, and neither. The rate is required and which figure you send is the input mode, not
    // an option to combine — so both of these are the client's error, answered at 400 rather
    // than guessed at.
    @Test
    void sendingBothRatesOrNeitherIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/tables/" + tableId + "/rate")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ratePerMinute\":4,\"ratePerHour\":240}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("not both"));

        mockMvc.perform(put("/api/v1/tables/" + tableId + "/rate")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("A rate is required"));

        // The same rule on the table body, which is a separate DTO and so a separate mistake
        // to make.
        mockMvc.perform(post("/api/v1/tables")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Table 9\",\"ratePerMinute\":4,\"ratePerHour\":240}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("not both"));
    }

    // Re-rating a table mid-session must not reprice the session. The rate lives on the
    // segment, snapshotted when the session opened; pool_table_rate only answers "what would
    // a session opened now be charged".
    @Test
    void anOpenSessionKeepsItsSnapshottedRateWhenTheTableIsReRated() throws Exception {
        UUID sessionId = openBackdatedSession(45);

        changeRate("{\"ratePerHour\":600}");

        JsonNode closed = body(mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close")
                .with(user(principal()))).andExpect(status().isOk())).get("data");

        // 45 minutes at the 4.0000 it opened at, not at the 10.0000 the table now carries.
        assertThat(money(closed, "timeAmount")).isEqualByComparingTo("180.00");
        assertThat(money(onlyTable(), "ratePerMinute")).isEqualByComparingTo("10.0000");
    }

    // The edit that used to lose the owner's figure. PUT /tables posts the whole table back,
    // so a rename carries the rate with it; the comparison has to see that nothing about the
    // rate changed and leave the period alone. Comparing only the derived per-minute figure
    // was not enough — 240/hour and 4.0000/min derive the same rate.
    @Test
    void renamingAnHourlyTableKeepsItsHourlyFigureAndOpensNoRatePeriod() throws Exception {
        changeRate("{\"ratePerHour\":240}");
        long periodsBefore = ratePeriodCount();

        JsonNode renamed = body(mockMvc.perform(put("/api/v1/tables/" + tableId)
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Table Three\",\"tableNumber\":3,\"ratePerHour\":240,\"isActive\":true}"))
                .andExpect(status().isOk())).get("data");

        assertThat(renamed.get("name").asText()).isEqualTo("Table Three");
        assertThat(money(renamed, "ratePerHour")).isEqualByComparingTo("240.00");
        assertThat(money(renamed, "ratePerMinute")).isEqualByComparingTo("4.0000");
        assertThat(ratePeriodCount()).isEqualTo(periodsBefore);
    }

    // The other half of that: switching a table from per minute to the same rate stated
    // hourly IS a change, because the hourly figure is what the screen reads back. The derived
    // rate is identical, so a per-minute-only comparison would silently discard it.
    @Test
    void switchingInputModeAtTheSameRateStillRecordsTheHourlyFigure() throws Exception {
        long periodsBefore = ratePeriodCount();

        JsonNode table = changeRate("{\"ratePerHour\":240}").get("data");

        assertThat(money(table, "ratePerMinute")).isEqualByComparingTo("4.0000");
        assertThat(money(table, "ratePerHour")).isEqualByComparingTo("240.00");
        assertThat(ratePeriodCount()).isEqualTo(periodsBefore + 1);
    }

    private JsonNode changeRate(String json) throws Exception {
        return body(mockMvc.perform(put("/api/v1/tables/" + tableId + "/rate")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isOk()));
    }

    // Opens a session and moves its segment back, so a session of any length can be closed
    // without the test taking that long to run.
    private UUID openBackdatedSession(int minutes) throws Exception {
        UUID sessionId = UUID.fromString(body(mockMvc.perform(post("/api/v1/sessions")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + customerTypeId + "\"}"))
                .andExpect(status().isOk())).get("data").get("id").asText());

        actAs();
        SessionSegment segment = sessionSegmentRepository.findBySessionId(sessionId).getFirst();
        segment.setStartedAt(OffsetDateTime.now().minusMinutes(minutes));
        sessionSegmentRepository.saveAndFlush(segment);
        SecurityContextHolder.clearContext();

        return sessionId;
    }

    private BigDecimal closeAfterMinutes(int minutes) throws Exception {
        UUID sessionId = openBackdatedSession(minutes);
        JsonNode closed = body(mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close")
                .with(user(principal()))).andExpect(status().isOk())).get("data");
        assertThat(closed.get("billedMinutes").asInt()).isEqualTo(minutes);
        return money(closed, "timeAmount");
    }

    private JsonNode onlyTable() throws Exception {
        JsonNode floor = body(mockMvc.perform(get("/api/v1/tables").with(user(principal())))
                .andExpect(status().isOk())).get("data");
        return floor.get("tables").get(0);
    }

    private long ratePeriodCount() {
        actAs();
        try {
            return poolTableRateRepository.findAll().stream()
                    .filter(rate -> tableId.equals(rate.getPoolTableId()))
                    .count();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    // Read money as text, never as a double: 199.9980 has to stay 199.9980.
    private BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private AppUserDetails principal() {
        return new AppUserDetails(userId, branchId, "rate-tester",
                "unused", "Rate Tester", UserRole.ADMIN, true);
    }

    // For the direct repository access that moves the clock; the branch-scoped queries read
    // the branch from the security context.
    private void actAs() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal(), "unused", List.of()));
        SecurityContextHolder.setContext(context);
    }
}
