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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * The regular who plays tonight and pays next month.
 *
 * Two rules carry this whole feature, and both are about WHEN a thing happened rather than
 * what it was:
 *
 *   * The sale counts on the night it was played. leave-unpaid stamps closed_at, which is what
 *     bill.business_date is generated from, and settlement never touches it again. A debt
 *     collected five weeks later must not move a September sale onto an October report.
 *   * The money counts on the night it arrived. payment.business_date is generated from
 *     taken_at, so a debt settled in cash today lands in today's drawer and today's count.
 *
 * Everything below is one of those two rules, or the guard that stops a debt being recorded
 * with nobody's name against it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LeaveUnpaidTest {

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
    private PaymentRepository paymentRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private UUID branchId;
    private UUID userId;
    private UUID adminId;
    private UUID tableId;
    private UUID customerTypeId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("UNPAID" + UUID.randomUUID().toString().substring(0, 6));
        branch.setName("Leave Unpaid Test Branch");
        branch.setNextReceiptNo(1L);
        // Inactive, as BusinessDayCloseTest does it: an active test branch defeats the
        // single-branch default a global admin relies on, and these rows outlive the test.
        branch.setIsActive(false);
        branchId = branchRepository.saveAndFlush(branch).getId();

        userId = createUser("unpaid-tester-", UserRole.EMPLOYEE);
        adminId = createUser("unpaid-admin-", UserRole.ADMIN);

        PoolTable poolTable = new PoolTable();
        poolTable.setBranchId(branchId);
        poolTable.setName("Table 3");
        poolTable.setTableNumber(3);
        poolTable.setIsActive(true);
        tableId = poolTableRepository.saveAndFlush(poolTable).getId();

        // 6.00 a minute, so 109 minutes is exactly 654.00 and the acceptance figure is a real
        // multiplication rather than a number typed into an assertion.
        PoolTableRate rate = new PoolTableRate();
        rate.setBranchId(branchId);
        rate.setPoolTableId(tableId);
        rate.setRatePerMinute(new BigDecimal("6.0000"));
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

    // ── The acceptance case ─────────────────────────────────────────────────────

    @Test
    void junPlaysSixFiftyFourAndLeavesItOwed() throws Exception {
        UUID billId = playAndClose(109);

        JsonNode unpaid = body(leaveUnpaid(billId, "Jun", 0).andExpect(status().isOk())).get("data");

        // The totals froze. Before this, an unpaid bill's total_amount read 0.00 for ever.
        assertThat(new BigDecimal(unpaid.get("totalAmount").asText())).isEqualByComparingTo("654.00");
        // It took a receipt number from the same counter a paid checkout draws on.
        assertThat(unpaid.get("receiptNo").asLong()).isEqualTo(1L);
        assertThat(unpaid.get("latestNote").get("body").asText()).isEqualTo("Jun");
        assertThat(unpaid.get("latestNote").get("kind").asText()).isEqualTo("STAFF");
        assertThat(unpaid.get("daysOutstanding").asInt()).isZero();
        assertThat(unpaid.get("tableNames").get(0).asText()).isEqualTo("Table 3");
        // No cost or profit: one shape serves both roles.
        assertThat(unpaid.has("totalCost")).isFalse();

        // The night's gross includes it. This is the whole point: the sale was invisible before.
        JsonNode report = dailyReport(null);
        assertThat(new BigDecimal(report.get("totals").get("gross").asText()))
                .isEqualByComparingTo("654.00");
        assertThat(new BigDecimal(report.get("unsettledTonight").get("amount").asText()))
                .isEqualByComparingTo("654.00");
        assertThat(report.get("unsettledTonight").get("count").asInt()).isEqualTo(1);
        // Nothing was paid, so nothing is in the mix. An unsettled bill has no payment row.
        assertThat(report.get("paymentMix")).isEmpty();

        // And it is on the debt list, with the name.
        JsonNode listed = unpaidList();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("latestNote").get("body").asText()).isEqualTo("Jun");
    }

    // ── The name is not optional ────────────────────────────────────────────────

    @Test
    void aBillCannotBeLeftUnpaidWithNobodysNameOnIt() throws Exception {
        UUID billId = playAndClose(109);

        // 409 with a code, not a 400: this is a conflict with the state of the session, and the
        // client's repair is to make the note field required rather than to restate a sentence.
        leaveUnpaid(billId, null, 0)
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("SESSION_NOTE_REQUIRED")
                        .contains("Nobody's name is on this bill"));

        // Nothing was written: no receipt number burned, no status change.
        assertThat(unpaidList()).isEmpty();
        assertThat(bill(billId).get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void aNoteAlreadyWrittenDuringTheSessionIsEnough() throws Exception {
        UUID sessionId = openSessionId();
        UUID billId = billIdOf(sessionId);

        // The usual case: staff put the names on when the table opened.
        addNote(sessionId, "Jun and Marco").andExpect(status().isOk());
        runForMinutes(sessionId, 109);
        closeSession(sessionId);

        JsonNode unpaid = body(leaveUnpaid(billId, null, 0).andExpect(status().isOk())).get("data");
        assertThat(unpaid.get("latestNote").get("body").asText()).isEqualTo("Jun and Marco");
    }

    // A SYSTEM note is the server talking to itself. Letting it satisfy the rule would mean a
    // bill could be left unpaid on the strength of a line nobody typed.
    @Test
    void aSystemNoteDoesNotCountAsANameAgainstTheDebt() throws Exception {
        UUID sessionId = openSessionId();
        UUID billId = billIdOf(sessionId);
        runForMinutes(sessionId, 109);
        closeSession(sessionId);

        actAs(principal());
        jdbcTemplate.update("""
                insert into session_note (branch_id, session_id, kind, body, author_id)
                values (?, ?, 'SYSTEM', 'Settled by someone — CASH 654.00', ?)
                """, branchId, sessionId, userId);
        entityManager.clear();
        SecurityContextHolder.clearContext();

        leaveUnpaid(billId, null, 0)
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("SESSION_NOTE_REQUIRED"));
    }

    // ── A quick sale has nowhere to hang a name ─────────────────────────────────

    @Test
    void aQuickSaleCannotBeLeftUnpaid() throws Exception {
        // A quick sale is created and settled in one transaction, so no OPEN session-less bill
        // is reachable through the API at all. The refusal is asserted against a bill built
        // exactly as CheckoutServiceImpl builds one, to prove the guard rather than the absence.
        actAs(principal());
        UUID billId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into bill (id, branch_id, status, customer_type_id, opened_by,
                                  subtotal_time, subtotal_items, total_amount, total_cost)
                values (?, ?, 'OPEN', ?, ?, 0, 0, 0, 0)
                """, billId, branchId, customerTypeId, userId);
        entityManager.clear();
        SecurityContextHolder.clearContext();

        leaveUnpaid(billId, "Jun", 0)
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("A quick sale cannot be left unpaid"));
    }

    // ── The optimistic lock ─────────────────────────────────────────────────────

    @Test
    void aStaleBillVersionIsRejectedExactlyAsAtCheckout() throws Exception {
        UUID billId = playAndClose(109);

        leaveUnpaid(billId, "Jun", 7)
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("STALE_BILL_VERSION"));
    }

    // ── Settlement ──────────────────────────────────────────────────────────────

    @Test
    void aDebtIsPaidInFullOrNotAtAll() throws Exception {
        UUID billId = playAndClose(109);
        int version = leftUnpaidVersion(billId);

        pay(billId, "600.00", version)
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("must be settled in full")
                        .contains("Part payment is not recorded"));

        // Still outstanding, and still for the whole amount.
        assertThat(unpaidList()).hasSize(1);
        assertThat(new BigDecimal(unpaidList().get(0).get("totalAmount").asText()))
                .isEqualByComparingTo("654.00");
    }

    /*
     * The one that proves the revenue-recognition rule end to end.
     *
     * Jun plays, leaves it owed, and pays five weeks later. The night he played must report
     * exactly what it reported before he paid -- byte for byte, because a report that changes
     * after the owner has read and reconciled it is worse than one that was wrong from the
     * start. Today gets the money and nothing else.
     */
    @Test
    void aDebtSettledFiveWeeksLaterLeavesTheOriginalNightUntouched() throws Exception {
        UUID sessionId = openSessionId();
        UUID billId = billIdOf(sessionId);
        runForMinutes(sessionId, 109);
        closeSession(sessionId);
        leaveUnpaid(billId, "Jun", 0).andExpect(status().isOk());

        // Backdate the sale five weeks. business_date is generated from closed_at, so moving
        // that moves the night the sale reports under -- which is precisely the column
        // settlement must never touch.
        actAs(principal());
        jdbcTemplate.update("""
                update bill set opened_at = opened_at - interval '35 days',
                                closed_at = closed_at - interval '35 days',
                                unsettled_at = unsettled_at - interval '35 days'
                where id = ?
                """, billId);
        entityManager.clear();
        SecurityContextHolder.clearContext();

        LocalDate playedOn = LocalDate.parse(unpaidList().get(0).get("businessDate").asText());
        LocalDate today = branchRepository.currentBusinessDate();
        assertThat(playedOn).isEqualTo(today.minusDays(35));
        assertThat(unpaidList().get(0).get("daysOutstanding").asInt()).isEqualTo(35);

        String nightBefore = reportJson(playedOn);
        assertThat(nightBefore).contains("\"gross\":654.00");

        // Jun pays, in cash, today.
        JsonNode payment = body(pay(billId, "654.00", leftUnpaidVersion(billId))
                .andExpect(status().isOk())).get("data");
        assertThat(new BigDecimal(payment.get("amount").asText())).isEqualByComparingTo("654.00");
        // The payment is dated by taken_at, so it belongs to tonight's drawer.
        assertThat(LocalDate.parse(payment.get("businessDate").asText())).isEqualTo(today);

        entityManager.clear();

        /*
         * THE ASSERTION THIS TEST EXISTS FOR.
         *
         * Byte for byte, apart from `outstanding`, which is the one figure in the report that
         * is deliberately live: it answers "how much is owed right now" for the Attention band,
         * so of course it drops to zero when Jun pays, even on a report for a night five weeks
         * gone. Every figure that describes the NIGHT -- gross, cost, profit, the hour band,
         * table utilisation, per employee, and unsettledTonight -- is unchanged, which is what
         * makes an old report worth re-reading.
         */
        assertThat(withoutOutstanding(reportJson(playedOn)))
                .isEqualTo(withoutOutstanding(nightBefore));
        // Including the historical record that this night had a bill left unpaid on it. Keyed
        // on unsettled_at rather than on current status precisely so collection cannot erase it.
        assertThat(reportJson(playedOn)).contains("\"unsettledTonight\":{\"count\":1,\"amount\":654.00}");

        // Tonight shows the collection, and does NOT count the sale again.
        JsonNode tonight = dailyReport(today);
        assertThat(new BigDecimal(tonight.get("collectedToday").get("amount").asText()))
                .isEqualByComparingTo("654.00");
        assertThat(tonight.get("collectedToday").get("count").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(tonight.get("totals").get("gross").asText()))
                .isEqualByComparingTo("0.00");
        // Nothing outstanding any more.
        assertThat(new BigDecimal(tonight.get("outstanding").get("amount").asText()))
                .isEqualByComparingTo("0.00");

        // Today's drawer expects the money. This is what makes the cash count add up.
        actAs(principal());
        assertThat(paymentRepository.sumCashForBusinessDate(today)).isEqualByComparingTo("654.00");
        SecurityContextHolder.clearContext();

        // And the settlement note is on the thread, beside the name of who owed it.
        JsonNode notes = body(mockMvc.perform(get("/api/v1/bills/" + billId + "/notes")
                .with(user(principal()))).andExpect(status().isOk())).get("data");
        assertThat(notes).hasSize(2);
        assertThat(notes.get(0).get("body").asText()).isEqualTo("Jun");
        assertThat(notes.get(1).get("kind").asText()).isEqualTo("SYSTEM");
        assertThat(notes.get(1).get("body").asText()).contains("CASH").contains("654.00");
    }

    /*
     * The drawer only ever expects money that actually arrived.
     *
     * A bill left unpaid tonight is in tonight's GROSS but must not be in tonight's expected
     * cash, or every night with a debt on it would read as a shortfall and the one real
     * shortfall would be invisible among them.
     */
    @Test
    void anUnpaidBillIsInGrossButNotInTheDrawer() throws Exception {
        UUID billId = playAndClose(109);
        leaveUnpaid(billId, "Jun", 0).andExpect(status().isOk());

        LocalDate today = branchRepository.currentBusinessDate();
        assertThat(new BigDecimal(dailyReport(today).get("totals").get("gross").asText()))
                .isEqualByComparingTo("654.00");

        actAs(principal());
        assertThat(paymentRepository.sumCashForBusinessDate(today)).isEqualByComparingTo("0.00");
        SecurityContextHolder.clearContext();

        // The drawer is counted, and the debt is not expected in it. Nothing was collected, so
        // an empty till balances exactly -- if the 654 leaked into expectedCash, every night
        // with a debt on it would read as a shortfall and the one real shortfall would be lost
        // among them.
        JsonNode count = body(mockMvc.perform(post("/api/v1/business-day/" + today + "/cash-count")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedCash\":0.00}")).andExpect(status().isOk())).get("data");
        assertThat(new BigDecimal(count.get("expectedCash").asText())).isEqualByComparingTo("0.00");
        assertThat(new BigDecimal(count.get("variance").asText())).isEqualByComparingTo("0.00");

        // And the night closes with the debt outstanding -- informational, never a block.
        mockMvc.perform(post("/api/v1/business-day/" + today + "/close").with(user(principal())))
                .andExpect(status().isOk());
        assertThat(unpaidList()).hasSize(1);
    }

    // A debt is a decision; an OPEN bill with no live session is a mistake. The two lists must
    // never show the same bill, or the floor strip stops meaning "somebody forgot".
    @Test
    void aDebtLeavesTheNotCheckedOutStripAndJoinsTheDebtList() throws Exception {
        UUID billId = playAndClose(109);

        assertThat(unsettledList()).hasSize(1);
        assertThat(unpaidList()).isEmpty();

        leaveUnpaid(billId, "Jun", 0).andExpect(status().isOk());

        assertThat(unsettledList()).isEmpty();
        assertThat(unpaidList()).hasSize(1);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private UUID playAndClose(int minutes) throws Exception {
        UUID sessionId = openSessionId();
        UUID billId = billIdOf(sessionId);
        runForMinutes(sessionId, minutes);
        closeSession(sessionId);
        return billId;
    }

    // The version after finalisation, which the client would have re-read before settling.
    private int leftUnpaidVersion(UUID billId) throws Exception {
        if (unpaidList().isEmpty()) {
            leaveUnpaid(billId, "Jun", 0).andExpect(status().isOk());
        }
        entityManager.clear();
        return bill(billId).get("version").asInt();
    }

    private ResultActions leaveUnpaid(UUID billId, String note, int billVersion) throws Exception {
        String body = note == null
                ? "{\"billVersion\":" + billVersion + "}"
                : "{\"note\":\"" + note + "\",\"billVersion\":" + billVersion + "}";
        return mockMvc.perform(post("/api/v1/bills/" + billId + "/leave-unpaid")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions pay(UUID billId, String amount, int billVersion) throws Exception {
        return mockMvc.perform(post("/api/v1/bills/" + billId + "/payment")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"CASH\",\"amount\":" + amount
                        + ",\"tendered\":" + amount
                        + ",\"idempotencyKey\":\"" + UUID.randomUUID()
                        + "\",\"billVersion\":" + billVersion + "}"));
    }

    private ResultActions addNote(UUID sessionId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/notes")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"" + body + "\"}"));
    }

    private UUID openSessionId() throws Exception {
        JsonNode session = body(mockMvc.perform(post("/api/v1/sessions")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + customerTypeId + "\"}"))
                .andExpect(status().isOk())).get("data");
        return UUID.fromString(session.get("id").asText());
    }

    private UUID billIdOf(UUID sessionId) throws Exception {
        JsonNode session = body(mockMvc.perform(get("/api/v1/sessions/" + sessionId)
                .with(user(principal()))).andExpect(status().isOk())).get("data");
        return UUID.fromString(session.get("billId").asText());
    }

    private void closeSession(UUID sessionId) throws Exception {
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close").with(user(principal())))
                .andExpect(status().isOk());
    }

    // Moves the segment's start back rather than waiting, as UnsettledBillsTest does.
    private void runForMinutes(UUID sessionId, int minutes) {
        actAs(principal());
        SessionSegment segment = sessionSegmentRepository.findBySessionId(sessionId).getFirst();
        segment.setStartedAt(OffsetDateTime.now().minusMinutes(minutes));
        sessionSegmentRepository.saveAndFlush(segment);
        SecurityContextHolder.clearContext();
    }

    private JsonNode unpaidList() throws Exception {
        return body(mockMvc.perform(get("/api/v1/bills/unpaid").with(user(principal())))
                .andExpect(status().isOk())).get("data");
    }

    private JsonNode unsettledList() throws Exception {
        return body(mockMvc.perform(get("/api/v1/bills/unsettled").with(user(principal())))
                .andExpect(status().isOk())).get("data");
    }

    private JsonNode bill(UUID billId) throws Exception {
        return body(mockMvc.perform(get("/api/v1/bills/" + billId).with(user(principal())))
                .andExpect(status().isOk())).get("data");
    }

    // ADMIN: every figure on the daily report is cost or profit or leads to it.
    private JsonNode dailyReport(LocalDate businessDate) throws Exception {
        return body(mockMvc.perform(businessDate == null
                ? get("/api/v1/reports/daily").with(user(admin()))
                : get("/api/v1/reports/daily").param("date", businessDate.toString())
                        .with(user(admin())))
                .andExpect(status().isOk())).get("data");
    }

    // Strips the live outstanding block, which is a "right now" figure rather than a fact about
    // the night being reported.
    private String withoutOutstanding(String report) {
        return report.replaceAll(",\"outstanding\":\\{[^}]*}", "");
    }

    // The raw response text, for the byte-identical comparison.
    private String reportJson(LocalDate businessDate) throws Exception {
        return mockMvc.perform(get("/api/v1/reports/daily")
                        .param("date", businessDate.toString())
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private UUID createUser(String prefix, UserRole role) {
        AppUser appUser = new AppUser();
        appUser.setBranchId(branchId);
        appUser.setUsername(prefix + UUID.randomUUID());
        appUser.setPasswordHash("unused");
        appUser.setFullName("Unpaid Tester");
        appUser.setRole(role);
        appUser.setIsActive(true);
        return appUserRepository.saveAndFlush(appUser).getId();
    }

    private void actAs(AppUserDetails principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "unused", List.of()));
        SecurityContextHolder.setContext(context);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private AppUserDetails principal() {
        return new AppUserDetails(userId, branchId, "unpaid-tester",
                "unused", "Unpaid Tester", UserRole.EMPLOYEE, true);
    }

    private AppUserDetails admin() {
        return new AppUserDetails(adminId, branchId, "unpaid-admin",
                "unused", "Unpaid Tester", UserRole.ADMIN, true);
    }
}
