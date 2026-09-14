package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * The owner's month.
 *
 * Every figure here is seeded straight to the tables with a business date in the past, because
 * the point of the report is arithmetic across nights, and the API would stamp everything with
 * tonight. The nights are deliberately different shapes -- one carrying an UNSETTLED bill, one
 * a voided line, one a voided expense -- so that "the nights sum to the period" cannot be true
 * by accident of every night being the same.
 *
 * Fixed dates in August 2026 rather than dates relative to today, so a seeded night can never
 * fall on the boundary of a window and the hand-computed constants below stay constants.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PeriodReportTest {

    // Monday 10 to Wednesday 12 August 2026. Starts on a Monday and is under a week, so the
    // previous period is the same weekdays a week earlier.
    private static final LocalDate NIGHT_1 = LocalDate.of(2026, 8, 10);
    private static final LocalDate NIGHT_2 = LocalDate.of(2026, 8, 11);
    private static final LocalDate NIGHT_3 = LocalDate.of(2026, 8, 12);

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
    private BillLineRepository billLineRepository;

    @Autowired
    private CashCountRepository cashCountRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PoolTableRepository poolTableRepository;

    @Autowired
    private TableSessionRepository tableSessionRepository;

    @Autowired
    private SessionSegmentRepository sessionSegmentRepository;

    private UUID branchId;
    private UUID employeeId;
    private UUID adminId;
    private UUID waterId;
    // The product every ordinary line below is sold against. No stock, so it never appears in
    // the unsold list of the products test.
    private UUID stockId;
    private long nextReceipt = 1;
    private int nextSeq = 1;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("PRD" + UUID.randomUUID().toString().substring(0, 6));
        branch.setName("Period Report Test Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(false);
        branchId = branchRepository.saveAndFlush(branch).getId();

        employeeId = createUser("period-employee-", UserRole.EMPLOYEE);
        adminId = createUser("period-admin-", UserRole.ADMIN);

        ExpenseCategory water = new ExpenseCategory();
        water.setBranchId(branchId);
        water.setName("Water");
        water.setSortOrder(1);
        waterId = expenseCategoryRepository.saveAndFlush(water).getId();

        stockId = product("Period Stock", "90.00", "62.5000", "0.000");
    }

    // The three nights every test below can build on. Gross 1,610, cost of goods 770,
    // operating expenses 1,250 -- see threeNightsOfDifferentShapes for the working.
    private void seedThreeNights() {
        // Night 1: a settled bill and a debt. The debt counts as a sale.
        UUID a = bill(NIGHT_1, BillStatus.CLOSED, "180.00", "125.00");
        line(a, "San Miguel", "2", "90.00", "62.50", false);
        UUID b = bill(NIGHT_1, BillStatus.UNSETTLED, "180.00", "95.00");
        line(b, "Sisig", "1", "180.00", "95.00", false);

        // Night 2: a live line and a voided one, and a water delivery.
        UUID c = bill(NIGHT_2, BillStatus.CLOSED, "250.00", "100.00");
        line(c, "Platter", "1", "250.00", "100.00", false);
        line(c, "Rang up twice", "1", "90.00", "62.50", true);
        expense(NIGHT_2, "400.00", false);

        // Night 3: a big table and two expenses, one of them voided.
        UUID d = bill(NIGHT_3, BillStatus.CLOSED, "1000.00", "450.00");
        line(d, "Tournament", "1", "1000.00", "450.00", false);
        expense(NIGHT_3, "850.00", false);
        expense(NIGHT_3, "300.00", true);
    }

    /*
     * The nights sum to the period, to the centavo, for gross, cost of goods, operating expenses
     * and voids. Asserted against the daily report over the wire rather than against the
     * seeded constants, because the thing that can break is the two statements drifting apart
     * -- a widened predicate that quietly stopped counting UNSETTLED, say -- and the constants
     * would not notice which side moved.
     */
    @Test
    void theNightsOfARangeSumToThePeriodReportForThatRange() throws Exception {
        seedThreeNights();

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal opex = BigDecimal.ZERO;
        BigDecimal voids = BigDecimal.ZERO;
        int bills = 0;
        for (LocalDate night : new LocalDate[] { NIGHT_1, NIGHT_2, NIGHT_3 }) {
            JsonNode daily = data(mockMvc.perform(get("/api/v1/reports/daily?date=" + night)
                    .with(user(admin()))).andExpect(status().isOk()));
            gross = gross.add(money(daily.get("totals"), "gross"));
            cost = cost.add(money(daily.get("totals"), "cost"));
            opex = opex.add(money(daily.get("expenses"), "total"));
            voids = voids.add(money(daily.get("losses"), "voidAmount"));
            bills += daily.get("totals").get("bills").asInt();
        }
        // The seeds are not all the same shape, so this equality is not trivially true.
        assertThat(gross).isEqualByComparingTo("1610.00");
        assertThat(voids).isEqualByComparingTo("90.00");

        JsonNode period = period(NIGHT_1, NIGHT_3);
        assertThat(money(period.get("headline"), "gross")).isEqualByComparingTo(gross);
        assertThat(money(period.get("headline"), "costOfGoods")).isEqualByComparingTo(cost);
        assertThat(money(period.get("headline"), "operatingExpenses")).isEqualByComparingTo(opex);
        assertThat(money(period.get("givenAway"), "voidAmount")).isEqualByComparingTo(voids);
        assertThat(period.get("headline").get("bills").asInt()).isEqualTo(bills);

        // And the day rows are the nights themselves, in order, with the untraded night absent
        // from the count but present in the list.
        JsonNode byDay = period.get("byDay");
        assertThat(byDay).hasSize(3);
        assertThat(money(byDay.get(0), "gross")).isEqualByComparingTo("360.00");
        assertThat(money(byDay.get(1), "gross")).isEqualByComparingTo("250.00");
        assertThat(money(byDay.get(2), "gross")).isEqualByComparingTo("1000.00");
    }

    /*
     * NET on the seeded numbers, by hand:
     *
     *   gross  = 180 + 180 + 250 + 1000 = 1,610.00   (the UNSETTLED 180 counts; the voided 90 does not)
     *   cogs   = 125 +  95 + 100 +  450 =   770.00
     *   opex   = 400 + 850               = 1,250.00   (the voided 300 does not)
     *   gross profit = 1,610 - 770       =   840.00
     *   NET = 840 - 1,250                =  -410.00
     *
     * Three trading days, so gross per trading day is 536.67 and net per trading day -136.67.
     */
    @Test
    void netIsGrossLessCostOfGoodsLessOperatingExpenses() throws Exception {
        seedThreeNights();

        JsonNode headline = period(NIGHT_1, NIGHT_3).get("headline");

        assertThat(money(headline, "grossProfit")).isEqualByComparingTo("840.00");
        assertThat(money(headline, "net")).isEqualByComparingTo("-410.00");
        assertThat(headline.get("tradingDays").asInt()).isEqualTo(3);
        assertThat(money(headline, "grossPerTradingDay")).isEqualByComparingTo("536.67");
        assertThat(money(headline, "netPerTradingDay")).isEqualByComparingTo("-136.67");
        // 840 / 1610.
        assertThat(money(headline, "grossMarginPercent")).isEqualByComparingTo("52.2");
    }

    /*
     * Each night carries its operating cost BY CATEGORY, so the Every-night table can say
     * "rent" beside the figure instead of leaving the owner to guess why a Wednesday lost
     * money. Two categories on one night, largest first, summing to the row's own
     * operatingExpenses; a night with nothing paid out carries an empty list, not a null; and
     * the voided expense is out of it, exactly as it is out of the total.
     */
    @Test
    void everyNightCarriesItsOperatingExpensesByCategory() throws Exception {
        seedThreeNights();

        ExpenseCategory rent = new ExpenseCategory();
        rent.setBranchId(branchId);
        rent.setName("Rent");
        rent.setSortOrder(2);
        UUID rentId = expenseCategoryRepository.saveAndFlush(rent).getId();
        expense(NIGHT_3, rentId, "5000.00", false);

        JsonNode byDay = period(NIGHT_1, NIGHT_3).get("byDay");

        assertThat(byDay.get(0).get("expenses")).isEmpty();

        JsonNode second = byDay.get(1).get("expenses");
        assertThat(second).hasSize(1);
        assertThat(second.get(0).get("category").asText()).isEqualTo("Water");
        assertThat(money(second.get(0), "amount")).isEqualByComparingTo("400.00");

        JsonNode third = byDay.get(2).get("expenses");
        assertThat(third).hasSize(2);
        assertThat(third.get(0).get("category").asText()).isEqualTo("Rent");
        assertThat(money(third.get(0), "amount")).isEqualByComparingTo("5000.00");
        assertThat(third.get(1).get("category").asText()).isEqualTo("Water");
        assertThat(money(third.get(1), "amount")).isEqualByComparingTo("850.00");
        assertThat(money(byDay.get(2), "operatingExpenses")).isEqualByComparingTo("5850.00");
    }

    /*
     * Like for like. A month-to-date of five days compares against the first five days of the
     * month before -- not against the whole of it, and not against the five days immediately
     * before the 1st.
     *
     * Seeded so each wrong answer is a different number: a sale on 3 August (inside 1-5 Aug),
     * one on 6 August (inside the whole month, outside 1-5) and one on 30 August (inside the
     * five days before 1 September). Only the first may reach previousHeadline.
     */
    @Test
    void aMonthToDateComparesAgainstTheSameDaysOfThePreviousMonth() throws Exception {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 5);
        UUID inWindow = bill(LocalDate.of(2026, 8, 3), BillStatus.CLOSED, "300.00", "100.00");
        line(inWindow, "Counted", "1", "300.00", "100.00", false);
        UUID laterInMonth = bill(LocalDate.of(2026, 8, 6), BillStatus.CLOSED, "500.00", "100.00");
        line(laterInMonth, "Not counted: 6 Aug", "1", "500.00", "100.00", false);
        UUID justBefore = bill(LocalDate.of(2026, 8, 30), BillStatus.CLOSED, "700.00", "100.00");
        line(justBefore, "Not counted: 30 Aug", "1", "700.00", "100.00", false);

        JsonNode period = period(from, to);

        assertThat(period.get("previousFrom").asText()).isEqualTo("2026-08-01");
        assertThat(period.get("previousTo").asText()).isEqualTo("2026-08-05");
        assertThat(period.get("comparison").asText()).isEqualTo("SAME_DAYS_OF_PREVIOUS_MONTH");
        assertThat(money(period.get("previousHeadline"), "gross")).isEqualByComparingTo("300.00");
        assertThat(period.get("previousHeadline").get("tradingDays").asInt()).isEqualTo(1);
    }

    /*
     * A complete month compares to the complete month before it, not to the same NUMBER of
     * days. 1-30 September against 30 days of August would drop 31 August from every September
     * report; 1-28 February against 28 days of January would drop three.
     *
     * Seeded so the truncated answer is a different number: a sale on 31 August, which only the
     * whole-month rule reaches.
     */
    @Test
    void aCompleteMonthComparesAgainstTheWholePreviousMonth() throws Exception {
        UUID lastDay = bill(LocalDate.of(2026, 8, 31), BillStatus.CLOSED, "450.00", "100.00");
        line(lastDay, "31 August", "1", "450.00", "100.00", false);

        JsonNode september = period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertThat(september.get("previousFrom").asText()).isEqualTo("2026-08-01");
        assertThat(september.get("previousTo").asText()).isEqualTo("2026-08-31");
        assertThat(money(september.get("previousHeadline"), "gross")).isEqualByComparingTo("450.00");

        JsonNode february = period(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28));
        assertThat(february.get("previousFrom").asText()).isEqualTo("2027-01-01");
        assertThat(february.get("previousTo").asText()).isEqualTo("2027-01-31");
        assertThat(february.get("comparison").asText()).isEqualTo("SAME_DAYS_OF_PREVIOUS_MONTH");
    }

    // The other two shapes. A Monday-to-Wednesday against the Monday-to-Wednesday before it,
    // and an arbitrary range against the same number of days immediately before it.
    @Test
    void aWeekToDateComparesAgainstTheSameWeekdaysAndAnythingElseAgainstThePrecedingDays() throws Exception {
        JsonNode week = period(NIGHT_1, NIGHT_3);
        assertThat(week.get("previousFrom").asText()).isEqualTo("2026-08-03");
        assertThat(week.get("previousTo").asText()).isEqualTo("2026-08-05");
        assertThat(week.get("comparison").asText()).isEqualTo("SAME_DAYS_OF_PREVIOUS_WEEK");

        // Wednesday 12 to Sunday 16: five days, so 7 to 11 August.
        JsonNode arbitrary = period(NIGHT_3, LocalDate.of(2026, 8, 16));
        assertThat(arbitrary.get("previousFrom").asText()).isEqualTo("2026-08-07");
        assertThat(arbitrary.get("previousTo").asText()).isEqualTo("2026-08-11");
        assertThat(arbitrary.get("comparison").asText()).isEqualTo("PRECEDING_DAYS");
    }

    /*
     * Break-even: opex 3,000 at a 60% margin across 5 trading days is 1,000 a day.
     *
     *   3,000 / 0.60 / 5 = 1,000.00
     *
     * Five nights of a 100-peso bill costing 40, and one 3,000-peso expense. The actual gross
     * per trading day is 100, so the sentence on the page reads "you need 1,000, you averaged
     * 100".
     */
    @Test
    void breakEvenIsOperatingExpensesOverMarginOverTradingDays() throws Exception {
        for (int i = 0; i < 5; i++) {
            UUID id = bill(NIGHT_1.plusDays(i), BillStatus.CLOSED, "100.00", "40.00");
            line(id, "Beer", "1", "100.00", "40.00", false);
        }
        expense(NIGHT_1, "3000.00", false);

        JsonNode period = period(NIGHT_1, NIGHT_1.plusDays(4));

        assertThat(money(period.get("headline"), "grossMarginPercent")).isEqualByComparingTo("60.0");
        JsonNode breakEven = period.get("breakEven");
        assertThat(breakEven.get("computable").asBoolean()).isTrue();
        assertThat(money(breakEven, "requiredGrossPerTradingDay")).isEqualByComparingTo("1000.00");
        assertThat(money(breakEven, "actualGrossPerTradingDay")).isEqualByComparingTo("100.00");
    }

    // A range with nothing in it is zeros and "not enough sales", not a division by zero.
    @Test
    void aRangeWithNoSalesReturnsZerosAndTheNotComputableMarker() throws Exception {
        JsonNode period = period(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 12));

        JsonNode headline = period.get("headline");
        assertThat(money(headline, "gross")).isEqualByComparingTo("0.00");
        assertThat(money(headline, "net")).isEqualByComparingTo("0.00");
        assertThat(headline.get("tradingDays").asInt()).isZero();
        assertThat(headline.get("grossPerTradingDay").isNull()).isTrue();
        assertThat(headline.get("grossMarginPercent").isNull()).isTrue();

        assertThat(period.get("breakEven").get("computable").asBoolean()).isFalse();
        assertThat(period.get("breakEven").get("requiredGrossPerTradingDay").isNull()).isTrue();

        assertThat(period.get("byDay")).hasSize(7);
        assertThat(period.get("byDayOfWeek")).hasSize(7);
        assertThat(period.get("byDayOfWeek").get(0).get("avgGross").isNull()).isTrue();
        assertThat(period.get("givenAway").get("percentOfGross").isNull()).isTrue();
        assertThat(money(period.get("givenAway"), "total")).isEqualByComparingTo("0.00");
    }

    /*
     * Tables. A session moved between two tables credits each with its share of what the time
     * was charged, by minutes, and the weakest table is first.
     *
     *   Session 1: 60 min on Table A, then 60 min on Table B, charged 300 -> 150 each.
     *   Session 2: 60 min on Table B, charged 240.
     *
     * Table A: 60 min, 150.00, 150.00 an hour. Table B: 120 min, 390.00, 195.00 an hour. The
     * two sum to the 540.00 of time sold, and A is the weaker table so it comes first. One
     * trading day, so utilisation is over 19 hours: A 5.3%, B 10.5%.
     */
    @Test
    void tablesAreCreditedByWallClockShareOfTheChargedTimeAndSortedWeakestFirst() throws Exception {
        UUID tableA = poolTable("Period A", 1);
        UUID tableB = poolTable("Period B", 2);
        OffsetDateTime start = at(NIGHT_1, 20, 0);

        UUID bill1 = bill(NIGHT_1, BillStatus.CLOSED, "300.00", "0.00");
        UUID session1 = session(bill1, tableB, start, start.plusMinutes(120), 120, "300.00");
        segment(session1, tableA, 1, start, start.plusMinutes(60));
        segment(session1, tableB, 2, start.plusMinutes(60), start.plusMinutes(120));
        timeLine(bill1, session1, "300.00", 120);

        UUID bill2 = bill(NIGHT_1, BillStatus.CLOSED, "240.00", "0.00");
        UUID session2 = session(bill2, tableB, start, start.plusMinutes(60), 60, "240.00");
        segment(session2, tableB, 1, start, start.plusMinutes(60));
        timeLine(bill2, session2, "240.00", 60);

        JsonNode tables = period(NIGHT_1, NIGHT_1).get("tables");

        assertThat(tables).hasSize(2);
        JsonNode weakest = tables.get(0);
        assertThat(weakest.get("tableName").asText()).isEqualTo("Period A");
        assertThat(weakest.get("occupiedMinutes").asInt()).isEqualTo(60);
        assertThat(money(weakest, "timeRevenue")).isEqualByComparingTo("150.00");
        assertThat(money(weakest, "revenuePerOccupiedHour")).isEqualByComparingTo("150.00");
        assertThat(money(weakest, "utilisationPercent")).isEqualByComparingTo("5.3");

        JsonNode stronger = tables.get(1);
        assertThat(stronger.get("tableName").asText()).isEqualTo("Period B");
        assertThat(stronger.get("occupiedMinutes").asInt()).isEqualTo(120);
        assertThat(money(stronger, "timeRevenue")).isEqualByComparingTo("390.00");
        assertThat(money(stronger, "revenuePerOccupiedHour")).isEqualByComparingTo("195.00");
    }

    /*
     * Products. The thinnest margin is first, and stock that did not move is listed with the
     * capital sitting in it.
     */
    @Test
    void productsAreSortedThinnestMarginFirstAndUnsoldStockIsValuedAtAverageCost() throws Exception {
        UUID beer = product("Period Beer", "90.00", "62.5000", "24.000");
        UUID loss = product("Period Loss Leader", "50.00", "60.0000", "10.000");
        product("Period Shelf Warmer", "120.00", "80.0000", "5.000");
        product("Period Sold Out", "120.00", "80.0000", "0.000");

        UUID id = bill(NIGHT_1, BillStatus.CLOSED, "140.00", "122.50");
        line(id, beer, "Period Beer", "1", "90.00", "62.50", false);
        line(id, loss, "Period Loss Leader", "1", "50.00", "60.00", false);

        JsonNode period = period(NIGHT_1, NIGHT_1);

        JsonNode products = period.get("products");
        assertThat(products).hasSize(2);
        assertThat(products.get(0).get("name").asText()).isEqualTo("Period Loss Leader");
        assertThat(money(products.get(0), "margin")).isEqualByComparingTo("-10.00");
        assertThat(money(products.get(0), "marginPercent")).isEqualByComparingTo("-20.0");
        assertThat(products.get(1).get("name").asText()).isEqualTo("Period Beer");
        assertThat(money(products.get(1), "margin")).isEqualByComparingTo("27.50");

        // The shelf warmer is unsold with stock; the sold-out one has nothing on the shelf.
        JsonNode unsold = period.get("unsoldProducts");
        assertThat(unsold).hasSize(1);
        assertThat(unsold.get(0).get("name").asText()).isEqualTo("Period Shelf Warmer");
        assertThat(money(unsold.get(0), "capitalOnShelf")).isEqualByComparingTo("400.00");
    }

    /*
     * Cash discipline. Two of three trading nights counted, one of them short by 50, and the
     * night-1 debt still open inside the period.
     */
    @Test
    void cashDisciplineSumsVariancesCountsUncountedNightsAndAgesOpenDebts() throws Exception {
        seedThreeNights();
        cashCount(NIGHT_1, "1000.00", "950.00");
        cashCount(NIGHT_2, "250.00", "250.00");
        // An older debt, five weeks before the period, still open.
        bill(NIGHT_1.minusDays(35), BillStatus.UNSETTLED, "80.00", "20.00");

        JsonNode cash = period(NIGHT_1, NIGHT_3).get("cash");

        assertThat(money(cash, "varianceTotal")).isEqualByComparingTo("-50.00");
        assertThat(cash.get("nightsWithVariance").asInt()).isEqualTo(1);
        assertThat(cash.get("countedNights").asInt()).isEqualTo(2);
        assertThat(cash.get("uncountedTradingDays").asInt()).isEqualTo(1);

        JsonNode unsettled = cash.get("unsettled");
        assertThat(unsettled.get("thisPeriod").get("count").asInt()).isEqualTo(1);
        assertThat(money(unsettled.get("thisPeriod"), "amount")).isEqualByComparingTo("180.00");
        assertThat(unsettled.get("oneToFourWeeksBefore").get("count").asInt()).isZero();
        assertThat(unsettled.get("older").get("count").asInt()).isEqualTo(1);
        assertThat(money(unsettled.get("older"), "amount")).isEqualByComparingTo("80.00");
    }

    @Test
    void aRangeThatEndsBeforeItStartsOrRunsOverAYearIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/reports/period?from=2026-08-12&to=2026-08-10").with(user(admin())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/reports/period?from=2025-08-01&to=2026-08-02").with(user(admin())))
                .andExpect(status().isBadRequest());
        // Exactly a leap year is the ceiling, not over it.
        mockMvc.perform(get("/api/v1/reports/period?from=2025-08-01&to=2026-08-01").with(user(admin())))
                .andExpect(status().isOk());
    }

    // Every figure here is cost or profit, or leads to it.
    @Test
    void anEmployeeIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/reports/period?from=" + NIGHT_1 + "&to=" + NIGHT_3).with(user(employee())))
                .andExpect(status().isForbidden());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────

    private JsonNode period(LocalDate from, LocalDate to) throws Exception {
        return data(mockMvc.perform(get("/api/v1/reports/period?from=" + from + "&to=" + to)
                .with(user(admin()))).andExpect(status().isOk()));
    }

    // 20:00 Manila on the night, which is unambiguously inside the business day of that date.
    private OffsetDateTime at(LocalDate night, int hour, int minute) {
        return OffsetDateTime.of(night, LocalTime.of(hour, minute), ZoneOffset.ofHours(8));
    }

    private UUID bill(LocalDate night, BillStatus status, String total, String cost) {
        Bill bill = new Bill();
        bill.setBranchId(branchId);
        bill.setStatus(status);
        bill.setReceiptNo(nextReceipt++);
        bill.setOpenedBy(employeeId);
        bill.setOpenedAt(at(night, 19, 0));
        bill.setClosedBy(employeeId);
        bill.setClosedAt(at(night, 20, 0));
        if (status == BillStatus.UNSETTLED) {
            bill.setUnsettledBy(employeeId);
            bill.setUnsettledAt(at(night, 20, 0));
        }
        bill.setSubtotalTime(BigDecimal.ZERO);
        bill.setSubtotalItems(new BigDecimal(total));
        bill.setTotalAmount(new BigDecimal(total));
        bill.setTotalCost(new BigDecimal(cost));
        bill.setVersion(0);
        Bill saved = asUser(() -> billRepository.saveAndFlush(bill));
        assertThat(saved.getBusinessDate()).isEqualTo(night);
        return saved.getId();
    }

    private void line(UUID billId, String description, String quantity, String unitPrice, String unitCost,
                      boolean voided) {
        line(billId, stockId, description, quantity, unitPrice, unitCost, voided);
    }

    private void line(UUID billId, UUID productId, String description, String quantity, String unitPrice,
                      String unitCost, boolean voided) {
        BillLine line = new BillLine();
        line.setBranchId(branchId);
        line.setBillId(billId);
        line.setLineKind(BillLineKind.PRODUCT);
        line.setSeq(nextSeq++);
        line.setProductId(productId);
        line.setDescription(description);
        line.setQuantity(new BigDecimal(quantity));
        line.setUnitPrice(new BigDecimal(unitPrice));
        line.setUnitCost(new BigDecimal(unitCost));
        line.setCreatedBy(employeeId);
        if (voided) {
            line.setVoidedAt(OffsetDateTime.now());
            line.setVoidedBy(employeeId);
            line.setVoidReason("Rang up twice");
        }
        asUser(() -> billLineRepository.saveAndFlush(line));
    }

    private void timeLine(UUID billId, UUID sessionId, String amount, int minutes) {
        BillLine line = new BillLine();
        line.setBranchId(branchId);
        line.setBillId(billId);
        line.setLineKind(BillLineKind.TIME);
        line.setSeq(nextSeq++);
        line.setSessionId(sessionId);
        line.setDescription("Table time");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal(amount));
        line.setUnitCost(BigDecimal.ZERO);
        line.setBilledMinutes(minutes);
        line.setCreatedBy(employeeId);
        asUser(() -> billLineRepository.saveAndFlush(line));
    }

    private void expense(LocalDate night, String amount, boolean voided) {
        expense(night, waterId, amount, voided);
    }

    private void expense(LocalDate night, UUID categoryId, String amount, boolean voided) {
        Expense expense = new Expense();
        expense.setBranchId(branchId);
        expense.setExpenseCategoryId(categoryId);
        expense.setAmount(new BigDecimal(amount));
        expense.setPaidFromDrawer(true);
        expense.setIncurredAt(at(night, 20, 0));
        expense.setRecordedBy(employeeId);
        if (voided) {
            expense.setVoidedAt(OffsetDateTime.now());
            expense.setVoidedBy(employeeId);
            expense.setVoidReason("Paid by the owner directly");
        }
        Expense saved = asUser(() -> expenseRepository.saveAndFlush(expense));
        assertThat(saved.getBusinessDate()).isEqualTo(night);
    }

    private void cashCount(LocalDate night, String cashSales, String counted) {
        CashCount count = new CashCount();
        count.setBranchId(branchId);
        count.setBusinessDate(night);
        count.setCashSales(new BigDecimal(cashSales));
        count.setOpeningFloat(BigDecimal.ZERO);
        count.setCashExpenses(BigDecimal.ZERO);
        count.setCountedCash(new BigDecimal(counted));
        count.setCountedBy(employeeId);
        count.setCountedAt(at(night, 23, 0));
        asUser(() -> cashCountRepository.saveAndFlush(count));
    }

    private UUID product(String name, String price, String avgCost, String qtyOnHand) {
        Product product = new Product();
        product.setBranchId(branchId);
        product.setName(name);
        product.setSellingPrice(new BigDecimal(price));
        product.setAvgCost(new BigDecimal(avgCost));
        product.setQtyOnHand(new BigDecimal(qtyOnHand));
        product.setIsActive(true);
        return asUser(() -> productRepository.saveAndFlush(product)).getId();
    }

    private UUID poolTable(String name, int number) {
        PoolTable table = new PoolTable();
        table.setBranchId(branchId);
        table.setName(name);
        table.setTableNumber(number);
        table.setIsActive(true);
        return asUser(() -> poolTableRepository.saveAndFlush(table)).getId();
    }

    private UUID session(UUID billId, UUID poolTableId, OffsetDateTime opened, OffsetDateTime closed,
                         int billedMinutes, String timeAmount) {
        TableSession session = new TableSession();
        session.setBranchId(branchId);
        session.setBillId(billId);
        session.setPoolTableId(poolTableId);
        session.setStatus(SessionStatus.CLOSED);
        session.setOpenedBy(employeeId);
        // opened_at is @CreationTimestamp, so the row is stamped with now() whatever is set
        // here, and closed_at must not precede it. The report never reads either: the night is
        // the bill's, and the minutes are the segments'. The two arguments document the intent.
        session.setOpenedAt(opened);
        session.setClosedBy(employeeId);
        session.setClosedAt(OffsetDateTime.now().plusSeconds(closed.toEpochSecond() - opened.toEpochSecond()));
        session.setNeedsReview(false);
        session.setBilledMinutes(billedMinutes);
        session.setTimeAmount(new BigDecimal(timeAmount));
        return asUser(() -> tableSessionRepository.saveAndFlush(session)).getId();
    }

    private void segment(UUID sessionId, UUID poolTableId, int seq, OffsetDateTime started, OffsetDateTime ended) {
        SessionSegment segment = new SessionSegment();
        segment.setBranchId(branchId);
        segment.setSessionId(sessionId);
        segment.setPoolTableId(poolTableId);
        segment.setSeq(seq);
        segment.setRatePerMinute(new BigDecimal("2.5000"));
        segment.setStartedAt(started);
        segment.setEndedAt(ended);
        asUser(() -> sessionSegmentRepository.saveAndFlush(segment));
    }

    private UUID createUser(String prefix, UserRole role) {
        AppUser appUser = new AppUser();
        appUser.setBranchId(branchId);
        appUser.setUsername(prefix + UUID.randomUUID());
        appUser.setPasswordHash("unused");
        appUser.setFullName("Period Tester");
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

    private JsonNode data(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }

    private BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private AppUserDetails employee() {
        return new AppUserDetails(employeeId, branchId, "period-employee",
                "unused", "Period Tester", UserRole.EMPLOYEE, true);
    }

    private AppUserDetails admin() {
        return new AppUserDetails(adminId, branchId, "period-admin",
                "unused", "Period Tester", UserRole.ADMIN, true);
    }
}
