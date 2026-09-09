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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * The bill comes to 654, the customer asks for 600, the owner says yes.
 *
 * The worked figure throughout is that one: 96 minutes at 4.0000/min is 384.00 of table time,
 * a sisig and a beer are 270.00 of items, and the bill is 654.00. Charging 600.00 gives away
 * 54.00 -- the number the customer can see on their receipt and the owner sees on the
 * dashboard the next morning.
 *
 * The test that matters most here is the last one. A discount and a time reduction are
 * separate controls over overlapping money, and the way this goes wrong is not by failing but
 * by counting the same peso twice in the losses band.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BillDiscountTest {

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
    private AuditLogRepository auditLogRepository;

    @Autowired
    private SessionSegmentRepository sessionSegmentRepository;

    private UUID branchId;
    private UUID userId;
    private UUID tableId;
    private UUID customerTypeId;
    private UUID beerId;
    private UUID sisigId;
    private UUID cokeId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("DISC");
        branch.setName("Discount Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername("discount-tester-" + UUID.randomUUID());
        user.setPasswordHash("unused");
        user.setFullName("Discount Tester");
        user.setRole(UserRole.ADMIN);
        user.setIsActive(true);
        userId = appUserRepository.saveAndFlush(user).getId();

        PoolTable poolTable = new PoolTable();
        poolTable.setBranchId(branchId);
        poolTable.setName("Table 1");
        poolTable.setTableNumber(1);
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
        customerType.setAllowsRateOverride(true);
        customerType.setIsDefault(true);
        customerType.setSortOrder(1);
        customerTypeId = customerTypeRepository.saveAndFlush(customerType).getId();

        beerId = givenProduct("San Miguel Pale Pilsen", "90.00");
        sisigId = givenProduct("Sisig", "180.00");
        // The beer the customer orders AFTER the discount is agreed, in the test that proves
        // the discount does not follow the total up.
        cokeId = givenProduct("Coke", "50.00");
        receiveDelivery(beerId, "48", "62.50");
        receiveDelivery(sisigId, "20", "95.00");
        receiveDelivery(cokeId, "24", "22.00");
    }

    // The whole feature in one trace: 654 becomes 600, 54 is recorded, and only 600 can be paid.
    @Test
    void sixHundredAndFiftyFourChargedAtSixHundred() throws Exception {
        UUID billId = givenBillOf654();

        JsonNode bill = body(discount(billId, "600.00", "Regular customer, owner said yes")
                .andExpect(status().isOk())).get("data");
        assertThat(money(bill, "subtotalTime")).isEqualByComparingTo("384.00");
        assertThat(money(bill, "subtotalItems")).isEqualByComparingTo("270.00");
        // The server did the subtraction. The client sent 600 and never named 54.
        assertThat(money(bill, "discountAmount")).isEqualByComparingTo("54.00");
        assertThat(money(bill, "totalAmount")).isEqualByComparingTo("600.00");
        assertThat(bill.get("discountReason").asText()).isEqualTo("Regular customer, owner said yes");
        assertThat(bill.get("discountByUsername").isNull()).isFalse();

        pay(billId, "600.00", bill.get("version").asInt(), "disc-accept")
                .andExpect(status().isOk());

        Bill settled = asUser(() -> billRepository.findById(billId).orElseThrow());
        assertThat(settled.getStatus()).isEqualTo(BillStatus.CLOSED);
        assertThat(settled.getSubtotalTime()).isEqualByComparingTo("384.00");
        assertThat(settled.getSubtotalItems()).isEqualByComparingTo("270.00");
        assertThat(settled.getDiscountAmount()).isEqualByComparingTo("54.00");
        // subtotal_time + subtotal_items - discount_amount, and the figure bill_totals_chk and
        // bill_discount_within_subtotal_chk both had to admit.
        assertThat(settled.getTotalAmount()).isEqualByComparingTo("600.00");

        // The customer can see what they were given.
        JsonNode payload = body(mockMvc.perform(get("/api/v1/bills/" + billId + "/receipt")
                .with(user(principal()))).andExpect(status().isOk())).get("data").get("payload");
        assertThat(money(payload, "subtotalTime")).isEqualByComparingTo("384.00");
        assertThat(money(payload, "subtotalItems")).isEqualByComparingTo("270.00");
        assertThat(money(payload, "discountAmount")).isEqualByComparingTo("54.00");
        assertThat(payload.get("discountReason").asText()).isEqualTo("Regular customer, owner said yes");
        assertThat(money(payload, "totalAmount")).isEqualByComparingTo("600.00");

        assertThat(auditActions()).contains("BILL_DISCOUNTED");
    }

    /*
     * Paying the undiscounted amount is refused. Not merely discouraged -- this is the whole
     * point of putting the figure on the bill rather than leaving the counter to remember it.
     *
     * On its own bill and in its own test deliberately: settle() finalises the bill before it
     * compares the amount, so the rejection and a following success cannot share a transaction
     * the way two HTTP requests would. Under Spring the failed call rolls back; under this
     * class's @Transactional it would not, and the second attempt would fail for the wrong
     * reason. Keeping them apart is what makes the rejection mean what it says.
     */
    @Test
    void payingTheUndiscountedAmountIsRefused() throws Exception {
        UUID billId = givenBillOf654();
        JsonNode bill = body(discount(billId, "600.00", "Owner said yes")
                .andExpect(status().isOk())).get("data");

        pay(billId, "654.00", bill.get("version").asInt(), "disc-reject")
                .andExpect(status().isConflict());
    }

    // The three refusals. Two are bean validation and one is a state conflict, and they carry
    // the statuses this codebase gives each: 400 for a malformed request, 409 for a request
    // that is well formed and wrong about the bill.
    @Test
    void aDiscountWithoutAReasonOrOutsideTheBillIsRefused() throws Exception {
        UUID billId = givenBillOf654();

        discount(billId, "600.00", "").andExpect(status().isBadRequest());
        discount(billId, "-10.00", "Sorry").andExpect(status().isBadRequest());

        // Above the subtotal is a surcharge, not a discount. 409 rather than 400 because the
        // request is perfectly well formed — it is wrong about the state of this bill, which
        // is the same reason overrideBilledMinutes returns 409 for charging more minutes than
        // were played.
        discount(billId, "700.00", "Fat fingers").andExpect(status().isConflict());

        // Nothing stuck to the bill through any of that.
        assertThat(asUser(() -> billRepository.findById(billId).orElseThrow()).getDiscountAmount())
                .isEqualByComparingTo("0.00");
    }

    /*
     * chargeAmount is what is being CHARGED, so charging the full 654.00 is a discount of
     * ZERO — not a bill of nothing.
     *
     * This test asserted the right status under the wrong name: it read as "a bill cannot be
     * discounted to nothing", which is what the message used to claim and what the guard's
     * comment described. That scenario cannot arise at all — @DecimalMin("0.01") on
     * chargeAmount refuses a charge of zero before this guard is reached — so the only thing
     * this branch has ever caught is the no-op.
     *
     * Refused rather than accepted, because recording it would put a 0.00 giveaway in the
     * losses drill-down and the audit feed, which are screens the owner reads as controls.
     */
    @Test
    void chargingTheFullAmountIsRefusedAsANoOpRatherThanRecordedAsAZeroDiscount() throws Exception {
        UUID billId = givenBillOf654();
        discount(billId, "654.00", "Free game for the owner's cousin")
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("That is the full amount, so there is no discount to record")
                        // The old wording told the cashier the bill would come to nothing.
                        .doesNotContain("charge nothing at all"));

        assertThat(asUser(() -> billRepository.findById(billId).orElseThrow()).getDiscountAmount())
                .isEqualByComparingTo("0.00");
    }

    // The fixed-amount rule, which is the thing that surprises people: the discount is pesos,
    // agreed once, and it does not chase the total upwards.
    @Test
    void aLineAddedAfterTheDiscountRaisesTheTotalAndLeavesTheDiscountAlone() throws Exception {
        UUID billId = givenBillOf654();
        discount(billId, "600.00", "Owner said yes").andExpect(status().isOk());

        addLine(billId, cokeId, "1");

        JsonNode bill = body(mockMvc.perform(get("/api/v1/bills/" + billId).with(user(principal())))
                .andExpect(status().isOk())).get("data");
        assertThat(money(bill, "subtotalItems")).isEqualByComparingTo("320.00");
        // Still 54, not re-derived as a proportion of the new subtotal.
        assertThat(money(bill, "discountAmount")).isEqualByComparingTo("54.00");
        assertThat(money(bill, "totalAmount")).isEqualByComparingTo("650.00");
    }

    @Test
    void clearingTheDiscountRestoresTheFullAmount() throws Exception {
        UUID billId = givenBillOf654();
        discount(billId, "600.00", "Owner said yes").andExpect(status().isOk());

        JsonNode cleared = body(mockMvc.perform(delete("/api/v1/bills/" + billId + "/discount")
                .with(user(principal()))).andExpect(status().isOk())).get("data");
        assertThat(money(cleared, "discountAmount")).isEqualByComparingTo("0.00");
        assertThat(money(cleared, "totalAmount")).isEqualByComparingTo("654.00");
        assertThat(cleared.get("discountReason").isNull()).isTrue();

        /*
         * All four columns back to the shape a bill that was never discounted has --
         * bill_discount_together_chk admits no half-cleared row.
         *
         * READ FROM THE ROW, NOT FROM THE PERSISTENCE CONTEXT. This assertion named a database
         * constraint and never reached the database: the test is @Transactional, so the DELETE
         * handler's entity was still managed and findById handed it straight back with the
         * fields the handler had nulled in memory. discount_at is one of the columns the
         * service restamps, so an `updatable = false` on it -- the mapping mistake that has
         * bitten this project twice already -- would leave the row half-cleared for every later
         * reader while this went green.
         */
        detach();
        Bill bill = asUser(() -> billRepository.findById(billId).orElseThrow());
        assertThat(bill.getDiscountBy()).isNull();
        assertThat(bill.getDiscountAt()).isNull();
        assertThat(bill.getDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(bill.getDiscountReason()).isNull();

        assertThat(auditActions()).contains("BILL_DISCOUNTED", "BILL_DISCOUNT_CLEARED");
    }

    /*
     * THE ONE THAT MATTERS. A bill carrying both giveaways reports each separately and counts
     * neither twice.
     *
     * 96 minutes played, 60 charged: 36 minutes at 4.0000 is 144.00 of time not charged. The
     * TIME lines are rewritten first, so the bill then comes to 240.00 + 270.00 = 510.00, and
     * charging 500.00 gives away a further 10.00. The two are measured off different tables
     * over amounts that do not overlap, and the band must say 144.00 and 10.00 rather than
     * 154.00 twice or 144.00 once.
     */
    @Test
    void aTimeReductionAndADiscountAreReportedSeparatelyAndDoNotDoubleCount() throws Exception {
        JsonNode session = body(openSession()).get("data");
        UUID sessionId = UUID.fromString(session.get("id").asText());
        UUID billId = UUID.fromString(session.get("billId").asText());
        addLine(billId, sisigId, "1");
        addLine(billId, beerId, "1");
        startSegmentMinutesAgo(sessionId, 96);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close").with(user(principal())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/billed-minutes")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"billedMinutes\":60,\"reason\":\"Table light was out for half of it\"}"))
                .andExpect(status().isOk());

        JsonNode reduced = body(mockMvc.perform(get("/api/v1/bills/" + billId).with(user(principal())))
                .andExpect(status().isOk())).get("data");
        assertThat(money(reduced, "subtotalTime")).isEqualByComparingTo("240.00");
        assertThat(money(reduced, "totalAmount")).isEqualByComparingTo("510.00");

        JsonNode bill = body(discount(billId, "500.00", "Rounded it down for a regular")
                .andExpect(status().isOk())).get("data");
        assertThat(money(bill, "discountAmount")).isEqualByComparingTo("10.00");
        assertThat(money(bill, "totalAmount")).isEqualByComparingTo("500.00");

        pay(billId, "500.00", bill.get("version").asInt(), "both-adjustments")
                .andExpect(status().isOk());

        JsonNode losses = body(mockMvc.perform(get("/api/v1/reports/daily").with(user(principal())))
                .andExpect(status().isOk())).get("data").get("losses");
        assertThat(money(losses, "timeReductionForgone")).isEqualByComparingTo("144.00");
        assertThat(losses.get("reducedSessions").asInt()).isEqualTo(1);
        assertThat(money(losses, "discountAmount")).isEqualByComparingTo("10.00");
        assertThat(losses.get("discountBills").asInt()).isEqualTo(1);

        /*
         * Gross reports the DISCOUNTED figure, and that is the right answer: 500 pesos went in
         * the drawer, and a gross of 654 would leave the cash count short by 154 every time
         * with nothing on the report explaining it. The losses band above is what accounts for
         * the gap — 144 of time not charged plus 10 discounted is exactly the 154 between what
         * the meter and the menu said and what was collected.
         */
        JsonNode totals = body(mockMvc.perform(get("/api/v1/reports/daily").with(user(principal())))
                .andExpect(status().isOk())).get("data").get("totals");
        assertThat(money(totals, "gross")).isEqualByComparingTo("500.00");

        // And the drill-down lists the very rows the tile counted. Summed from the lines by the
        // same statement, so the two cannot drift.
        JsonNode detail = body(mockMvc.perform(get("/api/v1/reports/losses").with(user(principal())))
                .andExpect(status().isOk())).get("data").get("discounts");
        assertThat(detail.get("discountBills").asInt()).isEqualTo(1);
        assertThat(money(detail, "discountAmount")).isEqualByComparingTo("10.00");
        JsonNode line = detail.get("lines").get(0);
        assertThat(money(line, "subtotal")).isEqualByComparingTo("510.00");
        assertThat(money(line, "discountAmount")).isEqualByComparingTo("10.00");
        assertThat(money(line, "chargedAmount")).isEqualByComparingTo("500.00");
        assertThat(line.get("reason").asText()).isEqualTo("Rounded it down for a regular");
        assertThat(line.get("receiptNo").isNull()).isFalse();
        assertThat(line.get("actorUsername").isNull()).isFalse();
    }

    // ---- fixtures ------------------------------------------------------------------

    // 96 minutes at 4.0000/min is 384.00, a sisig and a beer are 270.00, and the session is
    // closed so the bill can be charged: 654.00 exactly.
    /*
     * Push the pending writes to the database and forget every managed entity, so the next read
     * has to come from the row rather than the persistence context.
     *
     * Without this, an assertion about what was PERSISTED cannot fail: a mapping that declares
     * a column unwritable makes Hibernate drop it from the UPDATE in silence, and the entity in
     * memory still holds the value that never left it.
     */
    private void detach() {
        entityManager.flush();
        entityManager.clear();
    }

    private UUID givenBillOf654() throws Exception {
        JsonNode session = body(openSession()).get("data");
        UUID sessionId = UUID.fromString(session.get("id").asText());
        UUID billId = UUID.fromString(session.get("billId").asText());

        addLine(billId, sisigId, "1");
        addLine(billId, beerId, "1");

        startSegmentMinutesAgo(sessionId, 96);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close").with(user(principal())))
                .andExpect(status().isOk());

        JsonNode bill = body(mockMvc.perform(get("/api/v1/bills/" + billId).with(user(principal())))
                .andExpect(status().isOk())).get("data");
        assertThat(money(bill, "totalAmount")).isEqualByComparingTo("654.00");
        return billId;
    }

    private ResultActions discount(UUID billId, String chargeAmount, String reason) throws Exception {
        return mockMvc.perform(post("/api/v1/bills/" + billId + "/discount")
                .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"chargeAmount\":" + chargeAmount + ",\"reason\":\"" + reason + "\"}"));
    }

    private ResultActions pay(UUID billId, String amount, int version, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/bills/" + billId + "/payment")
                .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"CASH\",\"amount\":" + amount + ",\"tendered\":" + amount
                        + ",\"idempotencyKey\":\"" + key + "\",\"billVersion\":" + version + "}"));
    }

    private List<String> auditActions() {
        return asUser(() -> auditLogRepository.findAll().stream().map(AuditLog::getAction).toList());
    }

    private ResultActions openSession() throws Exception {
        return mockMvc.perform(post("/api/v1/sessions")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\"" + customerTypeId + "\"}"))
                .andExpect(status().isOk());
    }

    private void addLine(UUID billId, UUID productId, String quantity) throws Exception {
        mockMvc.perform(post("/api/v1/bills/" + billId + "/lines")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk());
    }

    // Same shape as CheckoutAcceptanceTest's: opened_at is mapped non-updatable, and auto-close
    // relies on it agreeing with the first segment's start.
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

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    // Money is read as text, never as a double.
    private BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private AppUserDetails principal() {
        return new AppUserDetails(userId, branchId, "discount-tester",
                "unused", "Discount Tester", UserRole.ADMIN, true);
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
