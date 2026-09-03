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

// The lost-sale guard. A session can close without being paid — the browser dies, or staff
// misclick — and the table then reads free, so nothing on the floor points at that bill any
// more. Six such bills were already sitting in the database when this was written.
//
// A running table must NOT appear here: it is already visible on the floor, and listing it
// twice would have staff chasing a bill that is not yet due.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UnsettledBillsTest {

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
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private UUID branchId;
    private UUID userId;
    private UUID tableId;
    private UUID customerTypeId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("UNSETTLED");
        branch.setName("Unsettled Test Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername("unsettled-tester-" + UUID.randomUUID());
        user.setPasswordHash("unused");
        user.setFullName("Unsettled Tester");
        user.setRole(UserRole.EMPLOYEE);
        user.setIsActive(true);
        userId = appUserRepository.saveAndFlush(user).getId();

        PoolTable poolTable = new PoolTable();
        poolTable.setBranchId(branchId);
        poolTable.setName("Table 3");
        poolTable.setTableNumber(3);
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
    }

    @Test
    void aRunningSessionIsNotUnsettledButItsClosedUnpaidBillIs() throws Exception {
        JsonNode session = body(openSession().andExpect(status().isOk())).get("data");
        UUID sessionId = UUID.fromString(session.get("id").asText());
        UUID billId = UUID.fromString(session.get("billId").asText());

        // While the table is running the bill is on the floor already, so it must not appear.
        assertThat(unsettled()).isEmpty();

        runForMinutes(sessionId, 30);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close").with(user(principal())))
                .andExpect(status().isOk());

        // Closed and unpaid: the table now reads free, and this list is the only way back.
        JsonNode bills = unsettled();
        assertThat(bills).hasSize(1);

        JsonNode bill = bills.get(0);
        assertThat(bill.get("id").asText()).isEqualTo(billId.toString());
        assertThat(bill.get("tableNames").get(0).asText()).isEqualTo("Table 3");
        assertThat(bill.get("customerTypeName").asText()).isEqualTo("Regular");
        assertThat(bill.get("sessionEndedAt").isNull()).isFalse();
        // Carries no cost or profit: one shape serves both roles.
        assertThat(bill.has("totalCost")).isFalse();
        assertThat(bill.has("grossProfit")).isFalse();
    }

    @Test
    void aPaidBillDropsOffTheList() throws Exception {
        JsonNode session = body(openSession().andExpect(status().isOk())).get("data");
        UUID sessionId = UUID.fromString(session.get("id").asText());
        UUID billId = UUID.fromString(session.get("billId").asText());

        // Thirty minutes at 4.00 a minute, so there is something real to collect.
        runForMinutes(sessionId, 30);

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close").with(user(principal())))
                .andExpect(status().isOk());

        JsonNode listed = unsettled();
        assertThat(listed).hasSize(1);

        // The amount shown is computed from the live lines: bill.total_amount is still 0.00
        // until checkout finalises it, so reading the column would show a free game.
        BigDecimal shown = new BigDecimal(listed.get(0).get("totalAmount").asText());
        assertThat(shown).isEqualByComparingTo("120.00");
        assertThat(shown).isEqualByComparingTo(
                new BigDecimal(bill(billId).get("totalAmount").asText()));

        pay(billId, shown).andExpect(status().isOk());

        assertThat(unsettled()).isEmpty();
    }

    // The bill that used to disappear. Day close checks open sessions, not unpaid bills, so an
    // unsettled bill outlives the close — and while this list was scoped to the current
    // business day, it vanished from every screen the moment the date rolled.
    @Test
    void anUnsettledBillFromAnEarlierBusinessDayIsStillListed() throws Exception {
        JsonNode session = body(openSession().andExpect(status().isOk())).get("data");
        UUID sessionId = UUID.fromString(session.get("id").asText());
        UUID billId = UUID.fromString(session.get("billId").asText());

        runForMinutes(sessionId, 30);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close").with(user(principal())))
                .andExpect(status().isOk());

        // Backdate the whole bill into a previous night. business_date is generated from
        // opened_at, so moving that moves the day with it.
        actAs();
        jdbcTemplate.update("update bill set opened_at = opened_at - interval '3 days' where id = ?", billId);
        // The UPDATE went round Hibernate, so the Bill still sitting in the persistence context
        // holds the business_date it was inserted with. Without this clear the list below reads
        // that stale value and the assertion proves nothing.
        entityManager.clear();
        SecurityContextHolder.clearContext();

        JsonNode listed = unsettled();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("id").asText()).isEqualTo(billId.toString());
        // Compared against the BUSINESS date, not the calendar date. Those two differ only
        // between 00:00 and 05:00, so comparing with LocalDate.now() made this pass by luck
        // in the small hours and fail for the rest of the day.
        assertThat(java.time.LocalDate.parse(listed.get(0).get("businessDate").asText()))
                .isBefore(branchRepository.currentBusinessDate());
    }

    // A table opened and closed with no time on it totals 0.00, and payment validation
    // requires at least 0.01 — so such a bill can never be settled. Listing it would put a
    // permanent entry in the floor strip that no one can ever clear.
    @Test
    void aBillWithNothingToCollectIsNotListed() throws Exception {
        JsonNode session = body(openSession().andExpect(status().isOk())).get("data");
        UUID sessionId = UUID.fromString(session.get("id").asText());

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close").with(user(principal())))
                .andExpect(status().isOk());

        assertThat(unsettled()).isEmpty();
    }

    // Moves the segment's start back rather than waiting: the branch-scoped repositories read
    // the branch from the security context, so this runs as the principal.
    private void runForMinutes(UUID sessionId, int minutes) {
        actAs();
        SessionSegment segment = sessionSegmentRepository.findBySessionId(sessionId).getFirst();
        segment.setStartedAt(OffsetDateTime.now().minusMinutes(minutes));
        sessionSegmentRepository.saveAndFlush(segment);
        SecurityContextHolder.clearContext();
    }

    private void actAs() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal(), "unused", List.of()));
        SecurityContextHolder.setContext(context);
    }

    private JsonNode unsettled() throws Exception {
        return body(mockMvc.perform(get("/api/v1/bills/unsettled").with(user(principal())))
                .andExpect(status().isOk())).get("data");
    }

    private JsonNode bill(UUID billId) throws Exception {
        return body(mockMvc.perform(get("/api/v1/bills/" + billId).with(user(principal())))
                .andExpect(status().isOk())).get("data");
    }

    private org.springframework.test.web.servlet.ResultActions pay(UUID billId, BigDecimal amount) throws Exception {
        return mockMvc.perform(post("/api/v1/bills/" + billId + "/payment")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"CASH\",\"amount\":" + amount.toPlainString()
                        + ",\"tendered\":" + amount.toPlainString()
                        + ",\"idempotencyKey\":\"" + UUID.randomUUID()
                        + "\",\"billVersion\":0}"));
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

    private AppUserDetails principal() {
        return new AppUserDetails(userId, branchId, "unsettled-tester",
                "unused", "Unsettled Tester", UserRole.EMPLOYEE, true);
    }
}
