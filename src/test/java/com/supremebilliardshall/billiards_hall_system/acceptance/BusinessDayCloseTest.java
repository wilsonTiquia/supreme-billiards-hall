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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The owner's nightly control. Two things have to hold: a day closes exactly once, and a
// mistyped drawer count can be corrected by the owner but never by the person who counted it.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BusinessDayCloseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private CashCountRepository cashCountRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    // Needed to make the persistence assertions in this class capable of failing: this test is
    // @Transactional, so every MockMvc request shares one persistence context and a read-back
    // returns the in-memory entity rather than the row. See detach() below.
    @PersistenceContext
    private EntityManager entityManager;

    private UUID branchId;
    private UUID employeeId;
    private UUID adminId;
    private LocalDate businessDate;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("CLOSE" + UUID.randomUUID().toString().substring(0, 6));
        branch.setName("Close Test Branch");
        branch.setNextReceiptNo(1L);
        // Inactive: an active test branch defeats the single-branch default that a global
        // admin relies on, and these rows outlive the test.
        branch.setIsActive(false);
        branchId = branchRepository.saveAndFlush(branch).getId();

        employeeId = createUser("close-employee-", UserRole.EMPLOYEE);
        adminId = createUser("close-admin-", UserRole.ADMIN);

        businessDate = branchRepository.currentBusinessDate();

        /*
         * The takings the count is about, as real payment rows.
         *
         * They used to be implied by setting cashSales on the row by hand while the ledger held
         * nothing. That was fine while a correction only moved counted_cash, but PUT now
         * recomputes both sides of the subtraction, and a frozen figure with no payments behind
         * it would recompute to 0.00 and change what this test is measuring. The assertion
         * below is unchanged; the fixture is simply no longer lying about where 460.00 came
         * from.
         */
        cashSale(new BigDecimal("460.00"));

        // The drawer is counted, so the only thing left is the close itself.
        CashCount cashCount = new CashCount();
        cashCount.setBranchId(branchId);
        cashCount.setBusinessDate(businessDate);
        cashCount.setCashSales(new BigDecimal("460.00"));
        // No float on this branch, so the drawer is expected to hold takings alone and the
        // arithmetic here is the same as it was before the float existed.
        cashCount.setOpeningFloat(BigDecimal.ZERO);
        cashCount.setCountedCash(new BigDecimal("455.00"));
        cashCount.setCountedBy(employeeId);
        // Set by hand like every other required column here. counted_at is stamped by the
        // service that owns the event, not by the mapping, so a fixture that builds the row
        // directly has to supply it -- and the column's DEFAULT now() never applies, because
        // Hibernate names every mapped column in the INSERT and sends an explicit null.
        cashCount.setCountedAt(OffsetDateTime.now());
        cashCountRepository.saveAndFlush(cashCount);
    }

    @Test
    void aDayClosesOnceAndTheSecondAttemptIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/close").with(user(employee())))
                .andExpect(status().isOk());

        // Previously this returned 200 and wrote a second audit row, so nothing recorded when
        // the night actually ended.
        mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/close").with(user(employee())))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("was already closed"));
    }

    /*
     * A correction moves the figure and keeps the one it replaced.
     *
     * The second half is the whole reason the name says "AndTheOriginalSurvives", and nothing
     * asserted it: the test checked the two NEW figures and stopped, so a correction that
     * destroyed every trace of the 455.00 would have passed. A cashier who can re-count until
     * the variance reads zero is the thing this endpoint is admin-only to prevent, and the
     * audit row is where the evidence of the first count lives.
     */
    @Test
    void anAdminCorrectsAMistypedCountAndTheOriginalSurvives() throws Exception {
        JsonNode corrected = body(mockMvc.perform(correction(admin(), "545.00"))
                .andExpect(status().isOk())).get("data");

        assertThat(new BigDecimal(corrected.get("countedCash").asText())).isEqualByComparingTo("545.00");
        // The generated column is read back on update, not left at the old figure.
        assertThat(new BigDecimal(corrected.get("variance").asText())).isEqualByComparingTo("85.00");

        // And the figure it replaced is still recoverable, with the note that explains it.
        AuditLog correction = asUser(() -> auditLogRepository.findAll().stream()
                .filter(entry -> "CASH_COUNT_CORRECTED".equals(entry.getAction()))
                .findFirst().orElseThrow(() ->
                        new AssertionError("no CASH_COUNT_CORRECTED row was written")));

        assertThat(correction.getBefore()).as("the before snapshot").isNotNull();
        assertThat(new BigDecimal(String.valueOf(correction.getBefore().get("countedCash"))))
                .as("the original counted figure")
                .isEqualByComparingTo("455.00");
        assertThat(new BigDecimal(String.valueOf(correction.getAfter().get("countedCash"))))
                .isEqualByComparingTo("545.00");
        assertThat(correction.getNote()).isEqualTo("Miskeyed the hundreds");
    }

    // The whole point of restricting it: a cashier who can re-count until the variance reads
    // zero has defeated the only check on the drawer.
    @Test
    void anEmployeeCannotCorrectTheCount() throws Exception {
        mockMvc.perform(correction(employee(), "545.00"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theCountCannotBeCorrectedOnceTheDayIsClosed() throws Exception {
        mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/close").with(user(employee())))
                .andExpect(status().isOk());

        mockMvc.perform(correction(admin(), "545.00"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("no longer be corrected"));
    }

    @Test
    void correctingToTheSameFigureIsRejected() throws Exception {
        mockMvc.perform(correction(admin(), "455.00"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("already the counted figure"));
    }

    /*
     * The night the QA run found, in pesos.
     *
     * The drawer is counted at 460.00 while a table is still running; that table then finishes
     * and 3,241.00 in cash lands on the same business day. Every figure on the count row is now
     * describing a drawer that has moved.
     *
     * Before the fix this reported variance 0.00 with stale false, the day closed over it, and
     * neither PUT (day closed) nor recount (nothing traded since the close) could reach it
     * again. The assertions below are the three ways out: it is visible, the close refuses, and
     * the recount corrects it.
     */
    @Test
    void aDrawerCountedBeforeTheLastTakingsIsVisibleRefusesTheCloseAndCanBeRecounted() throws Exception {
        cashSale(new BigDecimal("3241.00"));

        JsonNode count = body(mockMvc.perform(get("/api/v1/business-day/" + businessDate + "/cash-count")
                .with(user(employee()))).andExpect(status().isOk())).get("data");

        String countedAtBefore = count.get("countedAt").asText();

        // Visible: the frozen figures still read the old night, and the row says so.
        assertThat(count.get("stale").asBoolean()).isTrue();
        assertThat(money(count, "cashSinceCount")).isEqualByComparingTo("3241.00");
        assertThat(money(count, "cashSales")).isEqualByComparingTo("460.00");
        assertThat(money(count, "expectedCash")).isEqualByComparingTo("460.00");

        // Refused, naming the amount rather than the word "stale".
        mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/close").with(user(employee())))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("3241.00")
                        .contains("has been taken")
                        .contains("Recount the drawer before closing"));

        /*
         * Corrected. The drawer really holds 455.00 + 3,241.00, and counting that is the whole
         * night: cash_sales recomputes to 3,701.00 and the variance comes back to 0.00 against
         * a figure that is now true, rather than 0.00 against one that was not.
         */
        JsonNode recounted = body(mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/recount")
                .with(user(employee()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedCash\":3696.00}"))
                .andExpect(status().isOk())).get("data");

        assertThat(money(recounted, "cashSales")).isEqualByComparingTo("3701.00");
        assertThat(money(recounted, "expectedCash")).isEqualByComparingTo("3701.00");
        assertThat(money(recounted, "variance")).isEqualByComparingTo("-5.00");

        /*
         * Everything above this line reads the response, which is built from the entity still
         * in the persistence context -- so it would pass even if the recount's writes never
         * reached the row. Everything below reads the row.
         *
         * That distinction is the whole test. The first version of this assertion checked
         * `stale` on the response, went green, and the same sequence against the deployed jar
         * left the business day permanently uncloseable: counted_at carried @CreationTimestamp,
         * Hibernate left it out of the UPDATE, and the close -- a second request, reading the
         * database -- kept seeing the original count time and refusing for ever.
         */
        detach();

        JsonNode fromTheRow = body(mockMvc.perform(get("/api/v1/business-day/" + businessDate + "/cash-count")
                .with(user(employee()))).andExpect(status().isOk())).get("data");
        assertThat(fromTheRow.get("stale").asBoolean()).isFalse();
        assertThat(fromTheRow.get("countedAt").asText()).isNotEqualTo(countedAtBefore);
        assertThat(money(fromTheRow, "cashSales")).isEqualByComparingTo("3701.00");

        // And the night can now be signed off, on figures that describe the drawer.
        mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/close").with(user(employee())))
                .andExpect(status().isOk());
    }

    // A digital payment cannot move the drawer, so it must not block the close. A blocker that
    // fires on money the count never claimed to hold is the kind people learn to ignore.
    @Test
    void aDigitalSaleAfterTheCountDoesNotBlockTheClose() throws Exception {
        sale(new BigDecimal("500.00"), PaymentMethod.GCASH);

        mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/close").with(user(employee())))
                .andExpect(status().isOk());
    }

    /*
     * A correction moves both operands or it is not a correction.
     *
     * PUT used to write counted_cash alone, so on a night whose takings had grown, correcting
     * the count to what the drawer actually held produced a variance equal to the money the
     * frozen cash_sales had never seen -- a phantom discrepancy that looked authoritative.
     */
    @Test
    void correctingTheCountRecomputesTheTakingsTooRatherThanOneSideOfTheSubtraction() throws Exception {
        cashSale(new BigDecimal("3241.00"));

        JsonNode corrected = body(mockMvc.perform(correction(admin(), "3701.00"))
                .andExpect(status().isOk())).get("data");

        assertThat(money(corrected, "cashSales")).isEqualByComparingTo("3701.00");
        assertThat(money(corrected, "variance")).isEqualByComparingTo("0.00");
    }

    // Nothing has moved, so there is nothing to recount. The gate is the staleness of the
    // figures, not whether the day happens to be closed.
    @Test
    void aCountThatStillDescribesTheDrawerCannotBeRecounted() throws Exception {
        mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/recount")
                .with(user(employee()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedCash\":455.00}"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("still stands"));
    }

    private org.springframework.test.web.servlet.RequestBuilder correction(AppUserDetails as, String counted) {
        return put("/api/v1/business-day/" + businessDate + "/cash-count")
                .with(user(as))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedCash\":" + counted + ",\"note\":\"Miskeyed the hundreds\"}");
    }

    private UUID createUser(String prefix, UserRole role) {
        AppUser appUser = new AppUser();
        appUser.setBranchId(branchId);
        appUser.setUsername(prefix + UUID.randomUUID());
        appUser.setPasswordHash("unused");
        appUser.setFullName("Close Tester");
        appUser.setRole(role);
        appUser.setIsActive(true);
        return appUserRepository.saveAndFlush(appUser).getId();
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private AppUserDetails employee() {
        return new AppUserDetails(employeeId, branchId, "close-employee",
                "unused", "Close Tester", UserRole.EMPLOYEE, true);
    }

    /*
     * Push the pending writes to the database and forget every managed entity, so the next read
     * has to come from the row rather than the persistence context.
     *
     * Without this, an assertion about what was PERSISTED cannot fail: Hibernate silently drops
     * columns that a mapping annotation has declared unwritable, and the entity in memory still
     * holds the value that never left it.
     */
    private void detach() {
        entityManager.flush();
        entityManager.clear();
    }

    private BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private void cashSale(BigDecimal amount) {
        sale(amount, PaymentMethod.CASH);
    }

    /*
     * One finished sale on tonight's business date, settled.
     *
     * taken_at is pushed forward deliberately: staleness is "money moved after counted_at", and
     * @CreationTimestamp stamps the count row from the same JVM clock, so a payment written in
     * the same millisecond would be ambiguous. Half a minute is unambiguous and cannot cross
     * the 10:00 business-day roll from any time a test realistically runs.
     */
    private void sale(BigDecimal amount, PaymentMethod method) {
        // Allocated and advanced, the way finalise does it: receipt_no is unique, and more than
        // one sale per test would otherwise collide on the branch's unchanged next number.
        Branch owning = branchRepository.findById(branchId).orElseThrow();
        long receiptNo = owning.getNextReceiptNo();
        owning.setNextReceiptNo(receiptNo + 1);
        branchRepository.saveAndFlush(owning);

        Bill bill = new Bill();
        bill.setBranchId(branchId);
        bill.setStatus(BillStatus.CLOSED);
        bill.setReceiptNo(receiptNo);
        bill.setOpenedBy(employeeId);
        bill.setOpenedAt(OffsetDateTime.now());
        bill.setClosedBy(employeeId);
        bill.setClosedAt(OffsetDateTime.now());
        bill.setSubtotalTime(BigDecimal.ZERO);
        bill.setSubtotalItems(amount);
        bill.setTotalAmount(amount);
        bill.setTotalCost(BigDecimal.ZERO);
        bill.setVersion(0);
        UUID billId = asUser(() -> billRepository.saveAndFlush(bill)).getId();

        Payment payment = new Payment();
        payment.setBranchId(branchId);
        payment.setBillId(billId);
        payment.setMethod(method);
        payment.setAmount(amount);
        if (method == PaymentMethod.CASH) {
            payment.setTendered(amount);
            payment.setChangeGiven(BigDecimal.ZERO);
        } else {
            payment.setReferenceNo("REF-" + UUID.randomUUID());
        }
        payment.setIdempotencyKey(UUID.randomUUID().toString());
        payment.setTakenBy(employeeId);
        payment.setTakenAt(OffsetDateTime.now().plusSeconds(30));
        asUser(() -> paymentRepository.saveAndFlush(payment));
    }

    private <T> T asUser(Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    employee(), "unused", java.util.List.of()));
            SecurityContextHolder.setContext(context);
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private AppUserDetails admin() {
        return new AppUserDetails(adminId, branchId, "close-admin",
                "unused", "Close Tester", UserRole.ADMIN, true);
    }
}
