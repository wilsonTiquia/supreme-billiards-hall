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
import java.time.LocalDate;
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
 * The winner shows a screenshot, reads out a code, the cashier types it in.
 *
 * The worked figures throughout are the ones the owner gave: a PHP 240/hour table bills at
 * 4.0000/min, so three hours is 720.00 of table time and a two-hour voucher covers 480.00 of it.
 * The customer pays 240.00 for the extra hour, plus whatever they drank.
 *
 * The tests that matter most are the last three. Everything above them is arithmetic; those are
 * about a code being spendable exactly once, about what happens when it is worth more than the
 * session it is spent on, and about the losses band adding up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VoucherRedemptionTest {

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
    private VoucherRepository voucherRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private VoucherBatchRepository voucherBatchRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private SessionSegmentRepository sessionSegmentRepository;

    private UUID branchId;
    private UUID userId;
    private UUID tableId;
    private UUID regularTypeId;
    private UUID friendTypeId;
    private UUID beerId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("VCHR");
        branch.setName("Voucher Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername("voucher-tester-" + UUID.randomUUID());
        user.setPasswordHash("unused");
        user.setFullName("Voucher Tester");
        user.setRole(UserRole.ADMIN);
        user.setIsActive(true);
        userId = appUserRepository.saveAndFlush(user).getId();

        PoolTable poolTable = new PoolTable();
        poolTable.setBranchId(branchId);
        poolTable.setName("Table 1");
        poolTable.setTableNumber(1);
        poolTable.setIsActive(true);
        tableId = poolTableRepository.saveAndFlush(poolTable).getId();

        // PHP 240/hour, which is 4.0000/min exactly -- the owner's worked example, and chosen
        // so every figure below divides cleanly and a rounding error would be visible.
        PoolTableRate rate = new PoolTableRate();
        rate.setBranchId(branchId);
        rate.setPoolTableId(tableId);
        rate.setRatePerMinute(new BigDecimal("4.0000"));
        rate.setEffectiveFrom(OffsetDateTime.now().minusDays(1));
        poolTableRateRepository.saveAndFlush(rate);

        CustomerType regular = new CustomerType();
        regular.setBranchId(branchId);
        regular.setName("Regular");
        regular.setAllowsRateOverride(false);
        regular.setIsDefault(true);
        regular.setSortOrder(1);
        regularTypeId = customerTypeRepository.saveAndFlush(regular).getId();

        CustomerType friend = new CustomerType();
        friend.setBranchId(branchId);
        friend.setName("Friend of owner");
        friend.setAllowsRateOverride(true);
        friend.setIsDefault(false);
        friend.setSortOrder(2);
        friendTypeId = customerTypeRepository.saveAndFlush(friend).getId();

        beerId = givenProduct("San Miguel Pale Pilsen", "90.00");
        receiveDelivery(beerId, "48", "62.50");
    }

    /*
     * The whole feature in one trace, and the owner's headline case: three hours played on a
     * two-hour prize.
     *
     * 180 min at 4.0000 is 720.00. The voucher covers 120 of those minutes -- 480.00 -- and the
     * customer pays for the other hour plus their beer.
     */
    @Test
    void threeHoursPlayedOnATwoHourVoucherChargesTheDifference() throws Exception {
        String code = givenVoucherCode(2, LocalDate.now().plusMonths(1), "October Facebook draw");
        UUID billId = givenClosedSessionOf(180, true);

        JsonNode redemption = body(redeem(billId, code).andExpect(status().isOk())).get("data");
        assertThat(redemption.get("minutesCovered").asInt()).isEqualTo(120);
        // Nothing forfeited: the session outran the voucher, which is the ordinary case.
        assertThat(redemption.get("minutesForfeited").asInt()).isZero();
        assertThat(money(redemption, "voucherAmount")).isEqualByComparingTo("480.00");

        JsonNode bill = redemption.get("bill");
        assertThat(money(bill, "subtotalTime")).isEqualByComparingTo("720.00");
        assertThat(money(bill, "subtotalItems")).isEqualByComparingTo("90.00");
        assertThat(money(bill, "voucherAmount")).isEqualByComparingTo("480.00");
        assertThat(bill.get("voucherMinutesCovered").asInt()).isEqualTo(120);
        // 720 + 90 - 480. The extra hour and the beer.
        assertThat(money(bill, "totalAmount")).isEqualByComparingTo("330.00");

        pay(billId, "330.00", bill.get("version").asInt(), "voucher-accept")
                .andExpect(status().isOk());

        Bill settled = asUser(() -> billRepository.findById(billId).orElseThrow());
        assertThat(settled.getStatus()).isEqualTo(BillStatus.CLOSED);
        assertThat(settled.getVoucherAmount()).isEqualByComparingTo("480.00");
        assertThat(settled.getTotalAmount()).isEqualByComparingTo("330.00");

        // The customer's own document names the code and the hours it was worth.
        JsonNode payload = body(mockMvc.perform(get("/api/v1/bills/" + billId + "/receipt")
                .with(user(principal()))).andExpect(status().isOk())).get("data").get("payload");
        assertThat(payload.get("voucherCode").asText()).isEqualTo(code);
        assertThat(payload.get("voucherHoursCovered").asText()).isEqualTo("2");
        assertThat(money(payload, "voucherAmount")).isEqualByComparingTo("480.00");
        assertThat(money(payload, "totalAmount")).isEqualByComparingTo("330.00");

        assertThat(auditActions()).contains("VOUCHER_REDEEMED");
    }

    /*
     * THE RULE THAT SURPRISES PEOPLE. Ninety minutes played on a two-hour code: the table time
     * is covered in full and the remaining thirty minutes are gone. No change, no residual
     * balance, and the code is spent.
     */
    @Test
    void unusedMinutesAreForfeitedAndTheCodeIsSpent() throws Exception {
        String code = givenVoucherCode(2, LocalDate.now().plusMonths(1), "Tournament third place");
        UUID billId = givenClosedSessionOf(90, false);

        JsonNode redemption = body(redeem(billId, code).andExpect(status().isOk())).get("data");
        assertThat(redemption.get("minutesCovered").asInt()).isEqualTo(90);
        assertThat(redemption.get("minutesForfeited").asInt()).isEqualTo(30);
        // 90 min at 4.0000 is 360.00, and the bill had nothing else on it.
        assertThat(money(redemption, "voucherAmount")).isEqualByComparingTo("360.00");

        JsonNode bill = redemption.get("bill");
        assertThat(money(bill, "subtotalTime")).isEqualByComparingTo("360.00");
        // Nothing to pay for the table. Exactly zero, not a centavo of rounding either way --
        // a fully covered line contributes its own snapshotted total verbatim.
        assertThat(money(bill, "totalAmount")).isEqualByComparingTo("0.00");

        // Spent, and there is no balance left on it to spend again.
        Voucher voucher = asUser(() -> voucherRepository.findByCode(stored(code)).orElseThrow());
        assertThat(voucher.getRedeemedAt()).isNotNull();
        assertThat(voucher.getRedeemedBillId()).isEqualTo(billId);
        assertThat(voucher.getMinutes()).isEqualTo(120);
    }

    // The boundary between the two cases above: exactly the voucher's worth, nothing forfeited
    // and nothing to pay.
    @Test
    void aSessionExactlyTheLengthOfTheVoucherIsCoveredInFull() throws Exception {
        String code = givenVoucherCode(2, LocalDate.now().plusMonths(1), null);
        UUID billId = givenClosedSessionOf(120, false);

        JsonNode redemption = body(redeem(billId, code).andExpect(status().isOk())).get("data");
        assertThat(redemption.get("minutesCovered").asInt()).isEqualTo(120);
        assertThat(redemption.get("minutesForfeited").asInt()).isZero();
        assertThat(money(redemption, "voucherAmount")).isEqualByComparingTo("480.00");
        assertThat(money(redemption.get("bill"), "totalAmount")).isEqualByComparingTo("0.00");
    }

    /*
     * The same code twice, refused by naming where it went.
     *
     * "Already used" with nowhere to look is how a customer gets accused of trying it on. The
     * receipt number is what the cashier can actually pull up.
     */
    @Test
    void redeemingTheSameCodeTwiceIsRefusedNamingTheReceipt() throws Exception {
        String code = givenVoucherCode(2, LocalDate.now().plusMonths(1), null);
        UUID firstBill = givenClosedSessionOf(180, false);
        redeem(firstBill, code).andExpect(status().isOk());

        JsonNode first = body(mockMvc.perform(get("/api/v1/bills/" + firstBill)
                .with(user(principal()))).andExpect(status().isOk())).get("data");
        pay(firstBill, first.get("totalAmount").asText(), first.get("version").asInt(), "first-bill")
                .andExpect(status().isOk());
        Long receiptNo = asUser(() -> billRepository.findById(firstBill).orElseThrow().getReceiptNo());

        UUID secondBill = givenClosedSessionOf(60, false);
        String message = messageOf(redeem(secondBill, code).andExpect(status().isConflict()));
        assertThat(message).contains("already redeemed").contains("receipt #" + receiptNo);
    }

    // Expired, named by the date. A code that stopped being good on a date the customer can
    // read is a conversation; "invalid code" is an argument.
    @Test
    void anExpiredCodeIsRefusedNamingTheDate() throws Exception {
        // A fixed date that is permanently in the past, rather than one relative to today: the
        // assertion below names the month in full, and a relative date would either drift into
        // the future or have to be formatted by the same code it is checking.
        String code = givenExpiredVoucherCode(LocalDate.of(2026, 7, 31));
        UUID billId = givenClosedSessionOf(120, false);

        assertThat(messageOf(redeem(billId, code).andExpect(status().isConflict())))
                .contains("expired on 31 July 2026");
    }

    /*
     * A code belonging to another branch is simply not found.
     *
     * Not "that belongs to the other hall": the lookup is branch-scoped, so this branch has no
     * way to know the code exists — and telling one branch about another's giveaway would be
     * the wrong answer even if it did.
     */
    @Test
    void aCodeFromAnotherBranchIsNotFound() throws Exception {
        String foreignCode = givenVoucherInAnotherBranch();
        UUID billId = givenClosedSessionOf(120, false);

        assertThat(messageOf(redeem(billId, foreignCode).andExpect(status().isNotFound())))
                .contains("Voucher not found");
    }

    /*
     * One pricing story per session. A voucher is standard-rate time expressed as minutes, and
     * against a session already priced some other way it means nothing: on a flat fee there are
     * no minutes to cover, and on a promo or a friend rate the hall would be giving the same
     * time away twice.
     *
     * Each refusal NAMES which, because a cashier holding up a queue needs to know whether to
     * argue with the customer or with the till.
     */
    @Test
    void aVoucherIsRefusedOnAnySessionThatIsNotStandardRate() throws Exception {
        String promoCode = givenVoucherCode(2, LocalDate.now().plusMonths(1), null);
        UUID promoBill = givenClosedSessionOf(120, false,
                ",\"rateOverridePerHour\":120,\"rateOverrideKind\":\"PROMO\","
                        + "\"rateOverrideReason\":\"Happy hour\"", regularTypeId);
        assertThat(messageOf(redeem(promoBill, promoCode).andExpect(status().isConflict())))
                .contains("promo rate").contains("Table 1");

        String friendCode = givenVoucherCode(2, LocalDate.now().plusMonths(1), null);
        UUID friendBill = givenClosedSessionOf(120, false,
                ",\"rateOverridePerHour\":120", friendTypeId);
        assertThat(messageOf(redeem(friendBill, friendCode).andExpect(status().isConflict())))
                .contains("friend rate").contains("Table 1");

        String flatCode = givenVoucherCode(2, LocalDate.now().plusMonths(1), null);
        UUID flatBill = givenClosedSessionOf(120, false,
                ",\"flatAmount\":500,\"flatRateReason\":\"Saturday tournament\"", regularTypeId);
        assertThat(messageOf(redeem(flatBill, flatCode).andExpect(status().isConflict())))
                .contains("flat fee").contains("Table 1");

        // None of the three codes was touched: every refusal happens before the write.
        assertThat(asUser(() -> voucherRepository.findByCode(stored(promoCode)).orElseThrow()
                .getRedeemedAt())).isNull();
        assertThat(asUser(() -> voucherRepository.findByCode(stored(flatCode)).orElseThrow()
                .getRedeemedAt())).isNull();
    }

    // A voucher entered before the table is closed has nothing to cover, and burning the
    // customer's prize on a bill with no time on it is the one mistake with no way back.
    @Test
    void aVoucherOnABillWithNoTableTimeIsRefusedWithoutSpendingTheCode() throws Exception {
        String code = givenVoucherCode(2, LocalDate.now().plusMonths(1), null);
        JsonNode session = body(openSession("", regularTypeId)).get("data");
        UUID billId = UUID.fromString(session.get("billId").asText());
        addLine(billId, beerId, "1");

        assertThat(messageOf(redeem(billId, code).andExpect(status().isConflict())))
                .contains("no table time on this bill");
        assertThat(asUser(() -> voucherRepository.findByCode(stored(code)).orElseThrow()
                .getRedeemedAt())).isNull();
    }

    // For a code typed against the wrong bill. The code goes back in the pot and the bill goes
    // back to its full amount — both, or the mistake is only half undone.
    @Test
    void releasingAVoucherRestoresTheBillAndTheCode() throws Exception {
        String code = givenVoucherCode(2, LocalDate.now().plusMonths(1), null);
        UUID billId = givenClosedSessionOf(180, false);
        redeem(billId, code).andExpect(status().isOk());

        JsonNode released = body(mockMvc.perform(delete("/api/v1/bills/" + billId + "/voucher")
                .with(user(principal()))).andExpect(status().isOk())).get("data").get("bill");
        assertThat(money(released, "voucherAmount")).isEqualByComparingTo("0.00");
        assertThat(money(released, "totalAmount")).isEqualByComparingTo("720.00");
        assertThat(released.get("voucherCode").isNull()).isTrue();

        // All three columns back to the shape a bill that never had one has —
        // bill_voucher_together_chk admits no half-cleared row.
        Bill bill = asUser(() -> billRepository.findById(billId).orElseThrow());
        assertThat(bill.getVoucherId()).isNull();
        assertThat(bill.getVoucherMinutesCovered()).isNull();

        Voucher voucher = asUser(() -> voucherRepository.findByCode(stored(code)).orElseThrow());
        assertThat(voucher.getRedeemedAt()).isNull();
        assertThat(voucher.getRedeemedBy()).isNull();
        assertThat(voucher.getRedeemedBillId()).isNull();

        // And it can be spent again, which is the entire point of releasing it.
        UUID rightBill = givenClosedSessionOf(120, false);
        redeem(rightBill, code).andExpect(status().isOk());

        assertThat(auditActions()).contains("VOUCHER_REDEEMED", "VOUCHER_RELEASED");
    }

    /*
     * THE ONE THAT MATTERS FOR THE OWNER. A voucher and a discount on one bill, reported
     * separately, neither counted twice, and the drill-down summing to the tile.
     *
     * 180 min at 4.0000 is 720.00 of time and a beer is 90.00, so the bill is 810.00. The
     * voucher covers 480.00 of the table time, leaving 330.00, and charging 300.00 gives away
     * a further 30.00. The band must say 480.00 and 30.00 -- not 510.00 twice, and not one of
     * them silently absorbed into the other.
     */
    @Test
    void aVoucherAndADiscountAreReportedSeparatelyAndTheDrillDownSumsToTheTile() throws Exception {
        String code = givenVoucherCode(2, LocalDate.now().plusMonths(1), "October Facebook draw");
        UUID billId = givenClosedSessionOf(180, true);

        redeem(billId, code).andExpect(status().isOk());

        // The counter types the FINAL charge, and the server bounds it by what is left after
        // the voucher rather than by the undiscounted subtotal.
        JsonNode bill = body(discount(billId, "300.00", "Regular, rounded it down")
                .andExpect(status().isOk())).get("data");
        assertThat(money(bill, "voucherAmount")).isEqualByComparingTo("480.00");
        assertThat(money(bill, "discountAmount")).isEqualByComparingTo("30.00");
        assertThat(money(bill, "totalAmount")).isEqualByComparingTo("300.00");

        pay(billId, "300.00", bill.get("version").asInt(), "voucher-and-discount")
                .andExpect(status().isOk());

        JsonNode losses = body(mockMvc.perform(get("/api/v1/reports/daily").with(user(principal())))
                .andExpect(status().isOk())).get("data").get("losses");
        assertThat(losses.get("voucherCount").asInt()).isEqualTo(1);
        assertThat(money(losses, "voucherAmount")).isEqualByComparingTo("480.00");
        assertThat(losses.get("discountBills").asInt()).isEqualTo(1);
        assertThat(money(losses, "discountAmount")).isEqualByComparingTo("30.00");

        /*
         * Gross reports the post-voucher figure, which is the right answer: 300 pesos went in
         * the drawer, and a gross of 810 would leave the cash count short by 510 every night
         * with nothing explaining it. The band above is that explanation -- 480 given away as
         * a prize plus 30 discounted is exactly the 510 between what the meter and the menu
         * said and what was collected.
         */
        JsonNode totals = body(mockMvc.perform(get("/api/v1/reports/daily").with(user(principal())))
                .andExpect(status().isOk())).get("data").get("totals");
        assertThat(money(totals, "gross")).isEqualByComparingTo("300.00");

        JsonNode detail = body(mockMvc.perform(get("/api/v1/reports/losses").with(user(principal())))
                .andExpect(status().isOk())).get("data").get("vouchers");
        assertThat(detail.get("voucherCount").asInt()).isEqualTo(1);
        // Summed from the very rows listed beneath it by the same statement, so the drill-down
        // and the tile cannot drift.
        assertThat(money(detail, "voucherAmount")).isEqualByComparingTo("480.00");

        JsonNode line = detail.get("lines").get(0);
        assertThat(line.get("code").asText()).isEqualTo(code);
        assertThat(line.get("batchNote").asText()).isEqualTo("October Facebook draw");
        assertThat(line.get("voucherMinutes").asInt()).isEqualTo(120);
        assertThat(line.get("minutesCovered").asInt()).isEqualTo(120);
        assertThat(line.get("minutesForfeited").asInt()).isZero();
        assertThat(money(line, "voucherAmount")).isEqualByComparingTo("480.00");
        assertThat(line.get("poolTableName").asText()).isEqualTo("Table 1");
        assertThat(line.get("actorUsername").isNull()).isFalse();
        assertThat(line.get("receiptNo").isNull()).isFalse();
    }

    /*
     * THE PRIZE WINNER'S WHOLE NIGHT, end to end, because this is what the feature is FOR.
     *
     * She wins two hours in the Facebook draw, plays ninety minutes, buys nothing and walks out
     * paying nothing. Every step of that has to work: the code covers the table time, the bill
     * closes at 0.00 with no payment row, it appears in the owner's Sales list, and the 360.00
     * the hall gave up shows under Vouchers in Given away with the thirty unused minutes
     * recorded beside it.
     *
     * Until this existed the night dead-ended at "There is nothing to charge on this bill" with
     * a customer standing at the counter holding a prize.
     */
    @Test
    void aPrizeWinnerWhoOwesNothingFinishesTheNightAndLandsOnTheReport() throws Exception {
        String code = givenVoucherCode(2, LocalDate.now().plusMonths(1), "October Facebook draw");
        UUID billId = givenClosedSessionOf(90, false);

        JsonNode redemption = body(redeem(billId, code).andExpect(status().isOk())).get("data");
        assertThat(redemption.get("minutesForfeited").asInt()).isEqualTo(30);
        assertThat(money(redemption.get("bill"), "totalAmount")).isEqualByComparingTo("0.00");

        // Taking a payment is refused, and the message names the route that exists rather than
        // leaving the counter to work it out.
        assertThat(messageOf(pay(billId, "0.00", redemption.get("bill").get("version").asInt(),
                "prize-cash").andExpect(status().isBadRequest())))
                .contains("Amount");

        JsonNode receipt = body(mockMvc.perform(post("/api/v1/bills/" + billId + "/no-charge")
                .with(user(principal()))).andExpect(status().isOk())).get("data");
        JsonNode payload = receipt.get("payload");
        assertThat(money(payload, "totalAmount")).isEqualByComparingTo("0.00");
        // The chit says what happened rather than showing a blank payment block.
        assertThat(payload.get("noCharge").asBoolean()).isTrue();
        assertThat(payload.get("voucherCode").asText()).isEqualTo(code);
        assertThat(payload.get("voucherHoursCovered").asText()).isEqualTo("1.5");

        // Closed, numbered, and carrying NO payment row -- payment_amount_chk could not hold one.
        Bill closed = asUser(() -> billRepository.findById(billId).orElseThrow());
        assertThat(closed.getStatus()).isEqualTo(BillStatus.CLOSED);
        assertThat(closed.getReceiptNo()).isNotNull();
        assertThat(closed.getTotalAmount()).isEqualByComparingTo("0.00");
        assertThat(asUser(() -> paymentRepository.findByBillId(billId))).isEmpty();

        /*
         * IN THE OWNER'S SALES LIST. This is the half that was missing: the query joined
         * payment INNER, so a bill with no payment row simply vanished -- and the count agreed
         * with the short list, which is what makes that failure believable rather than obvious.
         */
        JsonNode sales = body(mockMvc.perform(get("/api/v1/bills")
                .param("businessDate", closed.getBusinessDate().toString())
                .with(user(principal()))).andExpect(status().isOk())).get("data");
        assertThat(sales.get("totalElements").asInt()).isEqualTo(1);
        JsonNode sale = sales.get("content").get(0);
        assertThat(sale.get("id").asText()).isEqualTo(billId.toString());
        assertThat(money(sale, "totalAmount")).isEqualByComparingTo("0.00");
        // Null rather than defaulted to CASH, which would show money that never went in.
        assertThat(sale.get("method").isNull()).isTrue();

        // And the giveaway is on the report, with what was used and what was thrown away.
        JsonNode losses = body(mockMvc.perform(get("/api/v1/reports/daily").with(user(principal())))
                .andExpect(status().isOk())).get("data").get("losses");
        assertThat(losses.get("voucherCount").asInt()).isEqualTo(1);
        assertThat(money(losses, "voucherAmount")).isEqualByComparingTo("360.00");

        JsonNode line = body(mockMvc.perform(get("/api/v1/reports/losses").with(user(principal())))
                .andExpect(status().isOk())).get("data").get("vouchers").get("lines").get(0);
        assertThat(money(line, "voucherAmount")).isEqualByComparingTo("360.00");
        assertThat(line.get("minutesCovered").asInt()).isEqualTo(90);
        assertThat(line.get("minutesForfeited").asInt()).isEqualTo(30);

        assertThat(auditActions()).contains("VOUCHER_REDEEMED", "BILL_CLOSED_NO_CHARGE");
    }

    // A bill with a figure on it must be unreachable from the no-charge route. This is the one
    // path that completes a sale without money, and that refusal is all that separates it from
    // a way to give any bill away.
    @Test
    void aBillWithSomethingToPayCannotBeClosedWithoutPayment() throws Exception {
        UUID billId = givenClosedSessionOf(180, true);

        assertThat(messageOf(mockMvc.perform(post("/api/v1/bills/" + billId + "/no-charge")
                .with(user(principal()))).andExpect(status().isConflict())))
                .contains("810.00").contains("has to be paid");
        /*
         * The refusal is what this asserts, and the rollback deliberately is not.
         *
         * finalise writes the bill before the gate reads its total, so under Spring the failed
         * request rolls all of it back -- status, closed_at and the receipt number alike. Under
         * this class's @Transactional it does not: the service joins the test's transaction
         * rather than opening its own, so the flushed row is still there to read and asserting
         * it had been undone would assert something this class cannot see. BillDiscountTest
         * makes the same note about its own rejection case.
         */
    }

    @Test
    void anArchivedBatchStopsRedemptionAndRestoringMakesItsUnusedCodeSpendable() throws Exception {
        String code = givenVoucherCode(2, LocalDate.now().plusMonths(1), "Archive test");
        UUID batchId = asUser(() -> voucherBatchRepository.findAll().get(0).getId());
        UUID billId = givenClosedSessionOf(90, false);
        mockMvc.perform(post("/api/v1/setup/vouchers/" + batchId + "/archive").with(user(principal())))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        assertThat(messageOf(redeem(billId, code).andExpect(status().isConflict()))).contains("archived");
        assertThat(body(mockMvc.perform(get("/api/v1/voucher-batches").with(user(principal())))
                .andExpect(status().isOk())).get("data")).isEmpty();
        mockMvc.perform(post("/api/v1/setup/vouchers/" + batchId + "/restore").with(user(principal())))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        redeem(billId, code).andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        assertThat(asUser(() -> voucherRepository.findAll().get(0).getRedeemedBillId())).isEqualTo(billId);
    }

    @Test
    void releasingAVoucherDoesNotMakeItsHistoryDeletable() throws Exception {
        String code = givenVoucherCode(2, LocalDate.now().plusMonths(1), "Released code");
        UUID batchId = asUser(() -> voucherBatchRepository.findAll().get(0).getId());
        UUID billId = givenClosedSessionOf(90, false);
        redeem(billId, code).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/bills/" + billId + "/voucher").with(user(principal())))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        assertThat(asUser(() -> voucherRepository.findAll().get(0).getRedeemedAt())).isNull();
        mockMvc.perform(delete("/api/v1/setup/vouchers/" + batchId).with(user(principal())))
                .andExpect(status().isConflict());
        entityManager.flush();
        entityManager.clear();
        assertThat(asUser(() -> voucherRepository.findAll().size())).isEqualTo(1);
        assertThat(auditActions()).contains("VOUCHER_REDEEMED", "VOUCHER_RELEASED");
    }

    // ---- fixtures ------------------------------------------------------------------

    /** One batch through the API, returning the first code in its display form. */
    private String givenVoucherCode(int hours, LocalDate expiresOn, String note) throws Exception {
        String body = "{\"hours\":" + hours + ",\"quantity\":1,\"expiresOn\":\"" + expiresOn + "\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
        JsonNode batch = body(mockMvc.perform(post("/api/v1/voucher-batches")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())).get("data");
        return batch.get("codes").get(0).get("code").asText();
    }

    /*
     * An already-expired code, written straight to the tables.
     *
     * It cannot be made through the API and should not be able to be: the request refuses a
     * past expiry, because a code that is worthless the moment it is printed is a mistake
     * rather than a giveaway. This is the row that exists because time passed.
     */
    private String givenExpiredVoucherCode(LocalDate expiresOn) {
        return asUser(() -> {
            VoucherBatch batch = new VoucherBatch();
            batch.setBranchId(branchId);
            batch.setMinutes(120);
            batch.setQuantity(1);
            batch.setExpiresOn(expiresOn);
            batch.setNote("Expired draw");
            batch.setCreatedBy(userId);
            VoucherBatch saved = voucherBatchRepository.saveAndFlush(batch);

            Voucher voucher = new Voucher();
            voucher.setBranchId(branchId);
            voucher.setBatchId(saved.getId());
            voucher.setCode("SBEXP1RE");
            voucher.setMinutes(120);
            voucher.setExpiresOn(expiresOn);
            voucherRepository.saveAndFlush(voucher);
            return "SB-EXP-1RE";
        });
    }

    /** A code that exists, in a branch this principal cannot see. */
    private String givenVoucherInAnotherBranch() {
        return asUser(() -> {
            Branch other = new Branch();
            other.setCode("VCHR2");
            other.setName("Other Voucher Branch");
            other.setNextReceiptNo(1L);
            other.setIsActive(false);
            UUID otherBranchId = branchRepository.saveAndFlush(other).getId();

            AppUser otherUser = new AppUser();
            otherUser.setBranchId(otherBranchId);
            otherUser.setUsername("other-owner-" + UUID.randomUUID());
            otherUser.setPasswordHash("unused");
            otherUser.setFullName("Other Owner");
            otherUser.setRole(UserRole.ADMIN);
            otherUser.setIsActive(true);
            UUID otherUserId = appUserRepository.saveAndFlush(otherUser).getId();

            VoucherBatch batch = new VoucherBatch();
            batch.setBranchId(otherBranchId);
            batch.setMinutes(120);
            batch.setQuantity(1);
            batch.setExpiresOn(LocalDate.now().plusMonths(1));
            batch.setCreatedBy(otherUserId);
            VoucherBatch saved = voucherBatchRepository.saveAndFlush(batch);

            Voucher voucher = new Voucher();
            voucher.setBranchId(otherBranchId);
            voucher.setBatchId(saved.getId());
            voucher.setCode("SBFORE1G");
            voucher.setMinutes(120);
            voucher.setExpiresOn(LocalDate.now().plusMonths(1));
            voucherRepository.saveAndFlush(voucher);
            return "SB-FOR-E1G";
        });
    }

    private UUID givenClosedSessionOf(int minutes, boolean withBeer) throws Exception {
        return givenClosedSessionOf(minutes, withBeer, "", regularTypeId);
    }

    /** A session run for `minutes` and closed, so its TIME lines exist to be covered. */
    private UUID givenClosedSessionOf(int minutes, boolean withBeer, String pricing,
                                      UUID customerTypeId) throws Exception {
        JsonNode session = body(openSession(pricing, customerTypeId)).get("data");
        UUID sessionId = UUID.fromString(session.get("id").asText());
        UUID billId = UUID.fromString(session.get("billId").asText());

        if (withBeer) {
            addLine(billId, beerId, "1");
        }
        startSegmentMinutesAgo(sessionId, minutes);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close").with(user(principal())))
                .andExpect(status().isOk());
        return billId;
    }

    private ResultActions openSession(String pricing, UUID customerTypeId) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions")
                        .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\",\"customerTypeId\":\""
                                + customerTypeId + "\"" + pricing + "}"))
                .andExpect(status().isOk());
    }

    private ResultActions redeem(UUID billId, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/bills/" + billId + "/voucher")
                .with(user(principal())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
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

    private List<String> auditActions() {
        return asUser(() -> auditLogRepository.findAll().stream().map(AuditLog::getAction).toList());
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String messageOf(ResultActions actions) throws Exception {
        return body(actions).get("message").asText();
    }

    // The stored form: what the display code becomes once the separators come off.
    private String stored(String displayCode) {
        return displayCode.replace("-", "").toUpperCase();
    }

    // Money is read as text, never as a double.
    private BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private AppUserDetails principal() {
        return new AppUserDetails(userId, branchId, "voucher-tester",
                "unused", "Voucher Tester", UserRole.ADMIN, true);
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
