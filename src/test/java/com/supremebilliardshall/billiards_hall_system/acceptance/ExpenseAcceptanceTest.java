package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
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
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * Operating expenses, and what they do to the drawer.
 *
 * The figures matter more here than in most of this suite, because this change dropped and
 * recreated cash_count.variance with a new term in it. A variance that is wrong by the amount of
 * a water delivery is a drawer that reads short and a member of staff who gets blamed for it, so
 * every test below asserts the peso figure rather than that the call returned 200.
 *
 * PHP 8,150 of cash sales and a PHP 850 delivery are the owner's own worked example.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExpenseAcceptanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private ExpenseCategoryRepository expenseCategoryRepository;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private UUID branchId;
    private UUID employeeId;
    private UUID adminId;
    private UUID waterId;
    private LocalDate businessDate;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("EXP" + UUID.randomUUID().toString().substring(0, 6));
        branch.setName("Expense Test Branch");
        branch.setNextReceiptNo(1L);
        // Inactive, like BusinessDayCloseTest: an active test branch defeats the single-branch
        // default a global admin relies on, and these rows outlive the test.
        branch.setIsActive(false);
        branchId = branchRepository.saveAndFlush(branch).getId();

        employeeId = createUser("expense-employee-", UserRole.EMPLOYEE);
        adminId = createUser("expense-admin-", UserRole.ADMIN);

        businessDate = branchRepository.currentBusinessDate();

        // This branch's own category. V15 seeds these per branch, but a branch created inside
        // the test was not there when the migration ran.
        ExpenseCategory water = new ExpenseCategory();
        water.setBranchId(branchId);
        water.setName("Water");
        water.setSortOrder(1);
        waterId = expenseCategoryRepository.saveAndFlush(water).getId();

        // PHP 8,150 of cash takings, as a settled bill and the payment for it, so the figure
        // the count freezes is the server's own sum rather than one typed into the row.
        cashSale(new BigDecimal("8150.00"));
    }

    /*
     * The owner's worked example, end to end.
     *
     * PHP 850 for water out of the drawer against PHP 8,150 of cash takings and no float: the
     * till should hold 7,300, and counting 7,300 has to read as balanced. Before this change it
     * would have read 850 short every time the water man came.
     */
    @Test
    void aCashExpenseReducesTheExpectedDrawerAndCountingItBalances() throws Exception {
        recordExpense(employee(), "850.00", true).andExpect(status().isOk());

        JsonNode count = data(count(employee(), "7300.00").andExpect(status().isOk()));

        assertThat(money(count, "cashSales")).isEqualByComparingTo("8150.00");
        assertThat(money(count, "cashExpenses")).isEqualByComparingTo("850.00");
        assertThat(money(count, "expectedCash")).isEqualByComparingTo("7300.00");
        // The generated column, which is the figure this whole change turns on.
        assertThat(money(count, "variance")).isEqualByComparingTo("0.00");
    }

    // The other half of the flag. Rent paid by transfer is operating cost all the same, but it
    // never touched the till, so the drawer arithmetic must not move.
    @Test
    void anExpenseNotPaidFromTheDrawerLeavesTheExpectedCashAlone() throws Exception {
        recordExpense(employee(), "850.00", false).andExpect(status().isOk());

        JsonNode count = data(count(employee(), "8150.00").andExpect(status().isOk()));

        assertThat(money(count, "cashExpenses")).isEqualByComparingTo("0.00");
        assertThat(money(count, "expectedCash")).isEqualByComparingTo("8150.00");
        assertThat(money(count, "variance")).isEqualByComparingTo("0.00");
    }

    /*
     * A void takes the money back out of the arithmetic and leaves the row where it was.
     *
     * Both halves matter: excluding it from the total is the point, and retaining it is this
     * project's rule for every correction — the counter has to still be able to see that they
     * recorded 850 and took it back, or they record it a second time.
     */
    @Test
    void voidingAnExpenseReturnsTheExpectedCashAndKeepsTheRowVisible() throws Exception {
        UUID expenseId = UUID.fromString(data(recordExpense(employee(), "850.00", true)).get("id").asText());

        JsonNode voided = data(mockMvc.perform(post("/api/v1/expenses/" + expenseId + "/void")
                        .with(user(employee()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Paid by the owner directly, not from the till\"}"))
                .andExpect(status().isOk()));

        assertThat(voided.get("voided").asBoolean()).isTrue();
        assertThat(voided.get("voidReason").asText()).contains("owner directly");
        assertThat(voided.get("voidedByUsername").asText()).startsWith("expense-employee");

        // Still listed, still flagged. The list is what the screen strikes through.
        JsonNode listed = data(mockMvc.perform(get("/api/v1/expenses?businessDate=" + businessDate)
                .with(user(employee()))).andExpect(status().isOk()));
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("voided").asBoolean()).isTrue();

        JsonNode count = data(count(employee(), "8150.00").andExpect(status().isOk()));
        assertThat(money(count, "cashExpenses")).isEqualByComparingTo("0.00");
        assertThat(money(count, "expectedCash")).isEqualByComparingTo("8150.00");
        assertThat(money(count, "variance")).isEqualByComparingTo("0.00");

        // The void is on the record with its actor, like every other one in this system.
        assertThat(auditActions()).contains("EXPENSE_RECORDED", "EXPENSE_VOIDED");
    }

    /*
     * The business day, not the calendar day.
     *
     * Recorded through the repository rather than the API because the API stamps incurred_at
     * with now() and the point is the generated column, which the JVM cannot reach. 02:00 Manila
     * on any day belongs to the night that started at 10:00 the previous evening.
     */
    @Test
    void anExpenseAtTwoInTheMorningLandsOnThePreviousCalendarDay() {
        OffsetDateTime twoAM = OffsetDateTime.of(2026, 9, 4, 2, 0, 0, 0, ZoneOffset.ofHours(8));

        Expense expense = new Expense();
        expense.setBranchId(branchId);
        expense.setExpenseCategoryId(waterId);
        expense.setAmount(new BigDecimal("850.00"));
        expense.setPaidFromDrawer(true);
        expense.setIncurredAt(twoAM);
        expense.setRecordedBy(employeeId);
        Expense saved = asUser(() -> expenseRepository.saveAndFlush(expense));

        assertThat(saved.getBusinessDate()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    /*
     * A closed night's arithmetic is finished, and the refusal has to say what to do instead.
     *
     * A message that only says no is one staff work around by writing the expense on paper,
     * which is the process this system replaced.
     */
    @Test
    void anExpenseCannotBeRecordedOnAClosedDayAndTheRefusalNamesTheRecount() throws Exception {
        count(employee(), "8150.00").andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/close").with(user(employee())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/expenses")
                        .with(user(employee()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseBody("850.00", true)))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("is closed")
                        .contains("Count the drawer again"));
    }

    // Voiding is refused on the same terms, and for the same reason: the frozen figure would
    // stop describing the drawer either way.
    @Test
    void anExpenseCannotBeVoidedOnAClosedDay() throws Exception {
        UUID expenseId = UUID.fromString(data(recordExpense(employee(), "850.00", true)).get("id").asText());
        count(employee(), "7300.00").andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/close").with(user(employee())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/expenses/" + expenseId + "/void")
                        .with(user(employee()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Changed my mind\"}"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("Count the drawer again"));
    }

    /*
     * The counter records and reads; only the owner writes the vocabulary.
     *
     * The read being open to an employee is deliberate rather than an oversight: an expense
     * carries no unit cost, no margin and no profit, so there is nothing here the cost rule
     * protects — and a counter who cannot see the 850 they just typed records it twice.
     */
    @Test
    void anEmployeeRecordsAndListsExpensesButCannotChangeTheCategories() throws Exception {
        recordExpense(employee(), "850.00", true).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/expenses").with(user(employee()))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/expense-categories").with(user(employee()))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/expense-categories")
                        .with(user(employee()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ice\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/expense-categories/" + waterId).with(user(employee())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/expense-categories")
                        .with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ice\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/expense-categories/" + waterId).with(user(admin())))
                .andExpect(status().isOk());
    }

    /*
     * The dashboard tile equals the sum of the day's live expenses, and the delta is against the
     * SAME WEEKDAY A WEEK EARLIER -- a Saturday against last Saturday, never against Friday.
     *
     * Asserted through the report itself rather than by re-running the CTE, because the thing
     * that can silently break is the SQL: a voided row left in, or the previous day read off the
     * wrong date, would both still return a number. The night before carries its own expense
     * here precisely so that reading it would produce a different figure, not the same one.
     */
    @Test
    void theDashboardTotalsTheDaysLiveExpensesAndComparesToTheSameWeekdayLastWeek() throws Exception {
        recordExpense(employee(), "850.00", true).andExpect(status().isOk());
        recordExpense(employee(), "1200.00", false).andExpect(status().isOk());

        // Voided, so it must not reach the tile — the row stays, the cost does not.
        UUID voided = UUID.fromString(data(recordExpense(employee(), "400.00", true)).get("id").asText());
        mockMvc.perform(post("/api/v1/expenses/" + voided + "/void")
                        .with(user(employee()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Rang it up twice\"}"))
                .andExpect(status().isOk());

        // Same weekday last week: PHP 500. Last night: PHP 700, which must NOT be the comparison.
        expenseOn(businessDate.minusDays(7), new BigDecimal("500.00"));
        expenseOn(businessDate.minusDays(1), new BigDecimal("700.00"));

        JsonNode report = data(mockMvc.perform(get("/api/v1/reports/daily?businessDate=" + businessDate)
                .with(user(admin()))).andExpect(status().isOk()));
        JsonNode expenses = report.get("expenses");

        assertThat(report.get("comparedTo").asText()).isEqualTo(businessDate.minusDays(7).toString());
        assertThat(money(expenses, "total")).isEqualByComparingTo("2050.00");
        assertThat(money(expenses, "previousTotal")).isEqualByComparingTo("500.00");

        // The breakdown has to add up to the tile beside it, or one of the two is lying.
        BigDecimal breakdown = BigDecimal.ZERO;
        for (JsonNode row : expenses.get("byCategory")) {
            breakdown = breakdown.add(money(row, "amount"));
        }
        assertThat(breakdown).isEqualByComparingTo("2050.00");
        assertThat(expenses.get("byCategory").get(0).get("category").asText()).isEqualTo("Water");
    }

    /*
     * A payout after the close is surfaced, exactly as a late sale already is.
     *
     * This is the blind spot the change would otherwise have created: adding expenses to the
     * variance gave the frozen figure a second way to stop being true, and unlike a late sale
     * nothing would have said so.
     */
    @Test
    void cashPaidOutAfterTheCloseMarksTheCountStale() throws Exception {
        count(employee(), "8150.00").andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/close").with(user(employee())))
                .andExpect(status().isOk());

        // Recorded straight to the repository: the API refuses a closed day, which is the
        // correct behaviour and not what is under test here. This is the night that was closed
        // at 03:00 and then paid the water man at 03:30.
        expenseOn(businessDate, new BigDecimal("850.00"));

        JsonNode count = data(mockMvc.perform(get("/api/v1/business-day/" + businessDate + "/cash-count")
                .with(user(employee()))).andExpect(status().isOk()));

        assertThat(count.get("expensesAfterClose").asInt()).isEqualTo(1);
        assertThat(money(count, "cashExpensesAfterClose")).isEqualByComparingTo("850.00");
        assertThat(count.get("stale").asBoolean()).isTrue();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────

    private void cashSale(BigDecimal amount) {
        Bill bill = new Bill();
        bill.setBranchId(branchId);
        bill.setStatus(BillStatus.CLOSED);
        bill.setReceiptNo(branchRepository.findById(branchId).orElseThrow().getNextReceiptNo());
        bill.setOpenedBy(employeeId);
        bill.setOpenedAt(OffsetDateTime.now());
        bill.setClosedBy(employeeId);
        bill.setClosedAt(OffsetDateTime.now());
        bill.setSubtotalTime(BigDecimal.ZERO);
        bill.setSubtotalItems(amount);
        bill.setTotalAmount(amount);
        bill.setTotalCost(new BigDecimal("2000.00"));
        bill.setVersion(0);
        UUID billId = asUser(() -> billRepository.saveAndFlush(bill)).getId();

        Payment payment = new Payment();
        payment.setBranchId(branchId);
        payment.setBillId(billId);
        payment.setMethod(PaymentMethod.CASH);
        payment.setAmount(amount);
        payment.setTendered(amount);
        payment.setChangeGiven(BigDecimal.ZERO);
        payment.setIdempotencyKey(UUID.randomUUID().toString());
        payment.setTakenBy(employeeId);
        payment.setTakenAt(OffsetDateTime.now());
        asUser(() -> paymentRepository.saveAndFlush(payment));
    }

    // An expense landed on a specific night, bypassing the API's closed-day guard.
    private void expenseOn(LocalDate on, BigDecimal amount) {
        Expense expense = new Expense();
        expense.setBranchId(branchId);
        expense.setExpenseCategoryId(waterId);
        expense.setAmount(amount);
        expense.setPaidFromDrawer(true);
        // Midday Manila is unambiguously inside the business day that carries that date.
        expense.setIncurredAt(OffsetDateTime.of(on, java.time.LocalTime.of(20, 0), ZoneOffset.ofHours(8)));
        expense.setRecordedBy(employeeId);
        Expense saved = asUser(() -> expenseRepository.saveAndFlush(expense));
        assertThat(saved.getBusinessDate()).isEqualTo(on);
    }

    private ResultActions recordExpense(AppUserDetails as, String amount, boolean paidFromDrawer) throws Exception {
        return mockMvc.perform(post("/api/v1/expenses")
                .with(user(as))
                .contentType(MediaType.APPLICATION_JSON)
                .content(expenseBody(amount, paidFromDrawer)));
    }

    private String expenseBody(String amount, boolean paidFromDrawer) {
        return "{\"expenseCategoryId\":\"" + waterId + "\",\"amount\":" + amount
                + ",\"note\":\"Water delivery\",\"paidFromDrawer\":" + paidFromDrawer + "}";
    }

    private ResultActions count(AppUserDetails as, String counted) throws Exception {
        return mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/cash-count")
                .with(user(as))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedCash\":" + counted + "}"));
    }

    // Read off audit_log itself, not inferred from the expense row: the point is that the
    // record was written, and deriving it from the thing it records would assert nothing.
    private java.util.List<String> auditActions() {
        return asUser(() -> auditLogRepository.search("expense", null,
                        businessDate.minusDays(1), businessDate.plusDays(1), PageRequest.of(0, 50)))
                .getContent().stream()
                .map(AuditLog::getAction)
                .toList();
    }

    private UUID createUser(String prefix, UserRole role) {
        AppUser appUser = new AppUser();
        appUser.setBranchId(branchId);
        appUser.setUsername(prefix + UUID.randomUUID());
        appUser.setPasswordHash("unused");
        appUser.setFullName("Expense Tester");
        appUser.setRole(role);
        appUser.setIsActive(true);
        return appUserRepository.saveAndFlush(appUser).getId();
    }

    // The branch-scoped repositories read @branchContext, which needs a principal even when the
    // call is not going through MockMvc.
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

    private JsonNode data(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }

    private BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private AppUserDetails employee() {
        return new AppUserDetails(employeeId, branchId, "expense-employee",
                "unused", "Expense Tester", UserRole.EMPLOYEE, true);
    }

    private AppUserDetails admin() {
        return new AppUserDetails(adminId, branchId, "expense-admin",
                "unused", "Expense Tester", UserRole.ADMIN, true);
    }
}
