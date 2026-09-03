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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BACKEND-SPEC.md section 4, acceptance tests 4 and 9.
//
// Test 4 guards a race: two counter tabs opening the same table. Test 9 guards the money —
// a wrong pause deduction produces a plausible bill that nobody queries until the drawer is
// short. Both drive real HTTP; only the clock is moved, because the alternative is a test
// that takes ninety minutes to run.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SessionAcceptanceTest {

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

    @Autowired
    private SessionPauseRepository sessionPauseRepository;

    private UUID branchId;
    private UUID userId;
    private UUID tableId;
    private UUID customerTypeId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("SESSTEST");
        branch.setName("Session Test Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername("session-tester-" + UUID.randomUUID());
        user.setPasswordHash("unused");
        user.setFullName("Session Tester");
        user.setRole(UserRole.EMPLOYEE);
        user.setIsActive(true);
        userId = appUserRepository.saveAndFlush(user).getId();

        PoolTable poolTable = new PoolTable();
        poolTable.setBranchId(branchId);
        poolTable.setName("Table 3");
        poolTable.setTableNumber(3);
        poolTable.setIsActive(true);
        tableId = poolTableRepository.saveAndFlush(poolTable).getId();

        // The worked trace's table: PHP 4.00 a minute.
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

    // Acceptance test 4.
    @Test
    void aSecondSessionOnTheSameTableIsRejected() throws Exception {
        openSession().andExpect(status().isOk());

        // 409, and it comes from table_session_one_open_per_table_key rather than from a
        // pre-select, which would only be a race with the tab that got there first.
        openSession()
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("already has an open session"));
    }

    // Acceptance test 9.
    @Test
    void ninetyMinutesWithATenMinutePauseBillsEightyMinutes() throws Exception {
        UUID sessionId = UUID.fromString(
                body(openSession().andExpect(status().isOk())).get("data").get("id").asText());

        OffsetDateTime startedAt = OffsetDateTime.now().minusMinutes(90);
        actAs();

        SessionSegment segment = sessionSegmentRepository.findBySessionId(sessionId).getFirst();
        segment.setStartedAt(startedAt);
        sessionSegmentRepository.saveAndFlush(segment);

        // A ten minute pause wholly inside the segment: minutes 30 to 40.
        SessionPause pause = new SessionPause();
        pause.setBranchId(branchId);
        pause.setSessionId(sessionId);
        pause.setPausedAt(startedAt.plusMinutes(30));
        pause.setResumedAt(startedAt.plusMinutes(40));
        pause.setPausedBy(userId);
        pause.setResumedBy(userId);
        sessionPauseRepository.saveAndFlush(pause);
        SecurityContextHolder.clearContext();

        // Running figure, before anything is stored.
        JsonNode live = body(mockMvc.perform(get("/api/v1/sessions/" + sessionId).with(user(principal())))
                .andExpect(status().isOk())).get("data");
        assertThat(live.get("billedMinutes").asInt()).isEqualTo(80);
        assertThat(money(live, "timeAmount")).isEqualByComparingTo("320.00");

        JsonNode closed = body(mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close")
                        .with(user(principal())))
                .andExpect(status().isOk())).get("data");

        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("billedMinutes").asInt()).isEqualTo(80);
        assertThat(money(closed, "timeAmount")).isEqualByComparingTo("320.00");

        // One TIME line per segment, carrying the same figure the session recorded.
        JsonNode segments = closed.get("segments");
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).get("billedMinutes").asInt()).isEqualTo(80);
        assertThat(money(segments.get(0), "amount")).isEqualByComparingTo("320.00");
    }

    // Decision 1, and it needs its own case: the 90-minus-10 arithmetic above lands exactly
    // on a minute boundary, so it passes whether the code floors or rounds up. This one does
    // not — 90m59s is the difference between billing 90 minutes and billing 91.
    @Test
    void partMinutesAreTruncatedNeverRoundedUp() throws Exception {
        UUID sessionId = UUID.fromString(
                body(openSession().andExpect(status().isOk())).get("data").get("id").asText());

        actAs();
        SessionSegment segment = sessionSegmentRepository.findBySessionId(sessionId).getFirst();
        segment.setStartedAt(OffsetDateTime.now().minusMinutes(90).minusSeconds(59));
        sessionSegmentRepository.saveAndFlush(segment);
        SecurityContextHolder.clearContext();

        JsonNode closed = body(mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close")
                        .with(user(principal())))
                .andExpect(status().isOk())).get("data");

        assertThat(closed.get("billedMinutes").asInt()).isEqualTo(90);
        assertThat(money(closed, "timeAmount")).isEqualByComparingTo("360.00");
    }

    // The on-screen counter is animated from billedSeconds, so it has to be exact. Anchoring
    // a local tick on the floored minute instead puts the display up to 59 seconds behind
    // real elapsed, which shows as the timer jumping backwards on every refresh and then
    // forwards again when it catches up. Money is unaffected: it still comes from
    // billedMinutes, which stays floored.
    @Test
    void billedSecondsIsExactWhileBilledMinutesStaysFloored() throws Exception {
        UUID sessionId = UUID.fromString(
                body(openSession().andExpect(status().isOk())).get("data").get("id").asText());

        // 90 minutes and 37 seconds: deliberately not on a minute boundary.
        actAs();
        SessionSegment segment = sessionSegmentRepository.findBySessionId(sessionId).getFirst();
        segment.setStartedAt(OffsetDateTime.now().minusSeconds(5437));
        sessionSegmentRepository.saveAndFlush(segment);
        SecurityContextHolder.clearContext();

        JsonNode live = body(mockMvc.perform(get("/api/v1/sessions/" + sessionId).with(user(principal())))
                .andExpect(status().isOk())).get("data");

        assertThat(live.get("billedMinutes").asInt()).isEqualTo(90);
        // The remainder survives. Upper bound leaves room for the test's own execution time.
        assertThat(live.get("billedSeconds").asInt()).isBetween(5437, 5450);
        // The two can never disagree: the minute figure is the seconds divided by sixty.
        assertThat(live.get("billedSeconds").asInt() / 60).isEqualTo(live.get("billedMinutes").asInt());
        // Billing is untouched — 90 minutes at 4.00.
        assertThat(money(live, "timeAmount")).isEqualByComparingTo("360.00");

        // The floor view drives the actual counter, so it has to carry the same figure.
        JsonNode floor = body(mockMvc.perform(get("/api/v1/tables").with(user(principal())))
                .andExpect(status().isOk())).get("data");
        JsonNode summary = null;
        for (JsonNode table : floor.get("tables")) {
            if (!table.get("session").isNull()) summary = table.get("session");
        }
        assertThat(summary).isNotNull();
        assertThat(summary.get("billedMinutes").asInt()).isEqualTo(90);
        assertThat(summary.get("billedSeconds").asInt()).isBetween(5437, 5450);
    }

    private org.springframework.test.web.servlet.ResultActions openSession() throws Exception {
        return mockMvc.perform(post("/api/v1/sessions")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + customerTypeId + "\"}"));
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    // Read money as text, never as a double: 320.00 has to stay 320.00.
    private BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private AppUserDetails principal() {
        return new AppUserDetails(userId, branchId, "session-tester",
                "unused", "Session Tester", UserRole.EMPLOYEE, true);
    }

    // For the direct repository access that moves the clock; the branch-scoped queries read
    // the branch from the security context.
    private void actAs() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal(), "unused", List.of()));
        SecurityContextHolder.setContext(context);
    }
}
