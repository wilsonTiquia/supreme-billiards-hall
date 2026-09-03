package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import com.supremebilliardshall.billiards_hall_system.service.SessionService;
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
import org.springframework.test.web.servlet.ResultActions;
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

// BACKEND-SPEC.md section 4, acceptance tests 1, 2, 3 and 5.
//
// Test 1 is the criterion for the whole project: if these six figures are right, the
// snapshotting, the billing maths, the void handling and the cost model are all right
// together. Every other test here protects one way that trace could silently stop being true.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CheckoutAcceptanceTest {

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
    private ProductRepository productRepository;

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private SessionSegmentRepository sessionSegmentRepository;

    @Autowired
    private SessionService sessionService;

    private UUID branchId;
    private UUID userId;
    private UUID tableId;
    private UUID customerTypeId;
    private UUID beerId;
    private UUID sisigId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("TRACE");
        branch.setName("Worked Trace Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername("trace-tester-" + UUID.randomUUID());
        user.setPasswordHash("unused");
        user.setFullName("Trace Tester");
        user.setRole(UserRole.ADMIN);
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

        // 48 beers so that two sold and one returned by the void leaves 47.
        beerId = givenProduct("San Miguel Pale Pilsen", "90.00");
        sisigId = givenProduct("Sisig", "180.00");
        receiveDelivery(beerId, "48", "62.50");
        receiveDelivery(sisigId, "20", "95.00");
    }

    // Acceptance test 1 — the worked trace.
    @Test
    void theWorkedTrace() throws Exception {
        JsonNode session = body(openSession()).get("data");
        UUID sessionId = UUID.fromString(session.get("id").asText());
        UUID billId = UUID.fromString(session.get("billId").asText());

        addLine(billId, beerId, "1");
        UUID voidedLine = addLine(billId, beerId, "1");
        addLine(billId, sisigId, "1");

        mockMvc.perform(post("/api/v1/bills/" + billId + "/lines/" + voidedLine + "/void")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Customer changed order\"}"))
                .andExpect(status().isOk());

        // 20:00 to 21:30 is ninety minutes of table time.
        startSegmentMinutesAgo(sessionId, 90);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close").with(user(principal())))
                .andExpect(status().isOk());

        // The preview writes nothing, and must already agree with what will be charged.
        JsonNode preview = body(mockMvc.perform(get("/api/v1/bills/" + billId + "/checkout")
                .with(user(principal()))).andExpect(status().isOk())).get("data");
        assertThat(preview.get("canCheckout").asBoolean()).isTrue();
        assertThat(money(preview.get("bill"), "totalAmount")).isEqualByComparingTo("630.00");

        body(pay(billId, "{\"method\":\"GCASH\",\"amount\":630.00,\"referenceNo\":\"GC-TRACE-1\","
                + "\"idempotencyKey\":\"trace-1\",\"billVersion\":0}").andExpect(status().isOk()));

        Bill bill = asUser(() -> billRepository.findById(billId).orElseThrow());
        assertThat(bill.getStatus()).isEqualTo(BillStatus.CLOSED);
        assertThat(bill.getSubtotalTime()).isEqualByComparingTo("360.00");
        assertThat(bill.getSubtotalItems()).isEqualByComparingTo("270.00");
        assertThat(bill.getTotalAmount()).isEqualByComparingTo("630.00");
        assertThat(bill.getTotalCost()).isEqualByComparingTo("157.50");
        assertThat(bill.getTotalAmount().subtract(bill.getTotalCost())).isEqualByComparingTo("472.50");
        // Derived, never hardcoded: the bill was opened just now, so it must land on whatever
        // business_date_of() says now is. A trace taken at 02:00 belongs to the previous day,
        // and that is exactly the behaviour worth asserting.
        assertThat(bill.getBusinessDate()).isEqualTo(branchRepository.currentBusinessDate());

        // 48 opening, two sold, one returned by the void.
        assertThat(asUser(() -> productRepository.findById(beerId).orElseThrow().getQtyOnHand()))
                .isEqualByComparingTo("47.000");

        // The voided line is still on the bill and still excluded from every total.
        JsonNode lines = body(mockMvc.perform(get("/api/v1/bills/" + billId).with(user(principal())))
                .andExpect(status().isOk())).get("data").get("lines");
        assertThat(lines).hasSize(4);
        assertThat(lines.valueStream().filter(line -> !line.get("voidedAt").isNull()).count()).isEqualTo(1);
    }

    // Acceptance test 2 — re-pricing must not reach back into a settled bill.
    @Test
    void raisingThePriceDoesNotRewriteHistory() throws Exception {
        UUID billId = settledTraceBill();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/products/" + beerId)
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"San Miguel Pale Pilsen\",\"sellingPrice\":100.00}"))
                .andExpect(status().isOk());

        Bill bill = asUser(() -> billRepository.findById(billId).orElseThrow());
        assertThat(bill.getSubtotalTime()).isEqualByComparingTo("360.00");
        assertThat(bill.getSubtotalItems()).isEqualByComparingTo("270.00");
        assertThat(bill.getTotalAmount()).isEqualByComparingTo("630.00");
        assertThat(bill.getTotalCost()).isEqualByComparingTo("157.50");

        // The frozen totals alone would pass even if the lines were being re-priced from the
        // catalog, so the lines themselves are checked: this is the path the daily report
        // takes, and the reason a re-price can never rewrite last month's profit.
        JsonNode lines = body(mockMvc.perform(get("/api/v1/bills/" + billId).with(user(principal())))
                .andExpect(status().isOk())).get("data").get("lines");

        BigDecimal recomputedTotal = BigDecimal.ZERO;
        BigDecimal recomputedCost = BigDecimal.ZERO;
        for (JsonNode line : lines) {
            if (!line.get("voidedAt").isNull()) {
                continue;
            }
            recomputedTotal = recomputedTotal.add(money(line, "lineTotal"));
            recomputedCost = recomputedCost.add(money(line, "lineCost"));
            if ("San Miguel Pale Pilsen".equals(line.get("description").asText())) {
                assertThat(money(line, "unitPrice")).isEqualByComparingTo("90.00");
                assertThat(money(line, "unitCost")).isEqualByComparingTo("62.5000");
            }
        }
        assertThat(recomputedTotal).isEqualByComparingTo("630.00");
        assertThat(recomputedCost).isEqualByComparingTo("157.50");
    }

    // Acceptance test 3 — the boundary the whole day's reporting hangs on. Asserted against
    // the database function, because that is what actually stamps every business_date.
    @Test
    void theBusinessDayRunsFromTenToFive() {
        assertThat(businessDateOf("2026-08-31 09:59:00+08")).isEqualTo(LocalDate.of(2026, 8, 30));
        assertThat(businessDateOf("2026-08-31 10:00:00+08")).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(businessDateOf("2026-09-01 02:00:00+08")).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(businessDateOf("2026-09-01 04:59:00+08")).isEqualTo(LocalDate.of(2026, 8, 31));
        // 05:00 is when the hall closes, not when the date rolls: it still belongs to the
        // night that just ended, which is what makes the 05:00 auto-close land correctly.
        assertThat(businessDateOf("2026-09-01 05:00:00+08")).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    // Acceptance test 5 — double checkout.
    @Test
    void aReplayedCheckoutTakesTheMoneyOnce() throws Exception {
        UUID billId = settledTraceBill();
        UUID firstPaymentId = asUser(() -> paymentRepository.findByBillId(billId).orElseThrow().getId());

        JsonNode replay = body(pay(billId, "{\"method\":\"GCASH\",\"amount\":630.00,\"referenceNo\":\"GC-TRACE-1\","
                + "\"idempotencyKey\":\"trace-1\",\"billVersion\":0}").andExpect(status().isOk())).get("data");

        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(UUID.fromString(replay.get("id").asText())).isEqualTo(firstPaymentId);
        // The money moved once. This is the assertion that matters.
        assertThat(asUser(() -> paymentRepository.findByBillId(billId))).isPresent();
        assertThat(asUser(() -> paymentRepository.count())).isEqualTo(1);
    }

    @Test
    void aStaleBillVersionIsRejected() throws Exception {
        JsonNode session = body(openSession()).get("data");
        UUID sessionId = UUID.fromString(session.get("id").asText());
        UUID billId = UUID.fromString(session.get("billId").asText());
        addLine(billId, beerId, "1");
        startSegmentMinutesAgo(sessionId, 90);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close").with(user(principal())))
                .andExpect(status().isOk());

        pay(billId, "{\"method\":\"CASH\",\"amount\":450.00,\"tendered\":500,"
                + "\"idempotencyKey\":\"stale-1\",\"billVersion\":7}")
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("STALE_BILL_VERSION"));

        assertThat(asUser(() -> paymentRepository.findByBillId(billId))).isEmpty();
    }

    // Decision 8 — the safety net, exercised without waiting for 5 AM.
    @Test
    void autoCloseCapsSessionsAtTheBusinessDayEnd() throws Exception {
        JsonNode session = body(openSession()).get("data");
        UUID sessionId = UUID.fromString(session.get("id").asText());
        startSegmentMinutesAgo(sessionId, 120);

        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(30);
        asUser(() -> sessionService.autoCloseOpenSessions(cutoff));

        TableSession closed = asUser(() -> entityManager.find(TableSession.class, sessionId));
        assertThat(closed.getStatus()).isEqualTo(SessionStatus.AUTO_CLOSED);
        assertThat(closed.getCloseKind()).isEqualTo(SessionCloseKind.AUTO_END_OF_DAY);
        assertThat(closed.getNeedsReview()).isTrue();
        // Capped at the cutoff, not at whatever time someone noticed: 120 minutes of table
        // time less the 30 that fell after the boundary.
        assertThat(closed.getBilledMinutes()).isEqualTo(90);
        assertThat(closed.getTimeAmount()).isEqualByComparingTo("360.00");
    }


    // The report has to agree with the bills it summarises. Run in a branch whose only bill
    // is the worked trace, so the daily figures must be exactly the trace's figures.
    @Test
    void theDailyReportAgreesWithTheBillsItSummarises() throws Exception {
        settledTraceBill();
        LocalDate businessDate = asUser(branchRepository::currentBusinessDate);

        JsonNode report = body(mockMvc.perform(get("/api/v1/reports/daily?date=" + businessDate)
                .with(user(principal()))).andExpect(status().isOk())).get("data");

        JsonNode totals = report.get("totals");
        assertThat(money(totals, "gross")).isEqualByComparingTo("630.00");
        assertThat(money(totals, "cost")).isEqualByComparingTo("157.50");
        assertThat(money(totals, "profit")).isEqualByComparingTo("472.50");
        assertThat(money(totals, "timeRevenue")).isEqualByComparingTo("360.00");
        assertThat(money(totals, "itemRevenue")).isEqualByComparingTo("270.00");
        assertThat(totals.get("bills").asInt()).isEqualTo(1);

        // The split must reconstruct the whole, not merely look plausible beside it.
        assertThat(money(totals, "timeRevenue").add(money(totals, "itemRevenue")))
                .isEqualByComparingTo(money(totals, "gross"));

        // And the per-employee breakdown must sum to the branch total, or one of them is lying.
        BigDecimal employeeGross = BigDecimal.ZERO;
        BigDecimal employeeCost = BigDecimal.ZERO;
        BigDecimal employeeProfit = BigDecimal.ZERO;
        for (JsonNode employee : report.get("perEmployee")) {
            employeeGross = employeeGross.add(money(employee, "gross"));
            employeeCost = employeeCost.add(money(employee, "cost"));
            employeeProfit = employeeProfit.add(money(employee, "profit"));
        }
        assertThat(report.get("perEmployee")).isNotEmpty();
        assertThat(employeeGross).isEqualByComparingTo(money(totals, "gross"));
        assertThat(employeeCost).isEqualByComparingTo(money(totals, "cost"));
        assertThat(employeeProfit).isEqualByComparingTo(money(totals, "profit"));

        // The voided beer is excluded from revenue but still counted as a loss.
        assertThat(report.get("losses").get("voidCount").asInt()).isEqualTo(1);
        assertThat(money(report.get("losses"), "voidAmount")).isEqualByComparingTo("90.00");
    }

    private UUID settledTraceBill() throws Exception {
        JsonNode session = body(openSession()).get("data");
        UUID sessionId = UUID.fromString(session.get("id").asText());
        UUID billId = UUID.fromString(session.get("billId").asText());
        addLine(billId, beerId, "1");
        UUID voidedLine = addLine(billId, beerId, "1");
        addLine(billId, sisigId, "1");
        mockMvc.perform(post("/api/v1/bills/" + billId + "/lines/" + voidedLine + "/void")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Customer changed order\"}"))
                .andExpect(status().isOk());
        startSegmentMinutesAgo(sessionId, 90);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close").with(user(principal())))
                .andExpect(status().isOk());
        pay(billId, "{\"method\":\"GCASH\",\"amount\":630.00,\"referenceNo\":\"GC-TRACE-1\","
                + "\"idempotencyKey\":\"trace-1\",\"billVersion\":0}").andExpect(status().isOk());
        return billId;
    }

    private LocalDate businessDateOf(String manilaTimestamp) {
        return (LocalDate) entityManager
                .createNativeQuery("select business_date_of(cast(:ts as timestamptz))", LocalDate.class)
                .setParameter("ts", manilaTimestamp)
                .getSingleResult();
    }

    // Moves the whole session back, not just its segment: in production opened_at and the
    // first segment's start are the same instant, and auto-close relies on that. opened_at is
    // mapped non-updatable, so this goes through native SQL.
    private void startSegmentMinutesAgo(UUID sessionId, int minutes) {
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

    private ResultActions openSession() throws Exception {
        return mockMvc.perform(post("/api/v1/sessions")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + customerTypeId + "\"}"))
                .andExpect(status().isOk());
    }

    private UUID addLine(UUID billId, UUID productId, String quantity) throws Exception {
        JsonNode added = body(mockMvc.perform(post("/api/v1/bills/" + billId + "/lines")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk())).get("data");
        return UUID.fromString(added.get("line").get("id").asText());
    }

    private ResultActions pay(UUID billId, String bodyJson) throws Exception {
        return mockMvc.perform(post("/api/v1/bills/" + billId + "/payment")
                .with(user(principal())).contentType(MediaType.APPLICATION_JSON).content(bodyJson));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    // Money is read as text, never as a double.
    private BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private AppUserDetails principal() {
        return new AppUserDetails(userId, branchId, "trace-tester",
                "unused", "Trace Tester", UserRole.ADMIN, true);
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
