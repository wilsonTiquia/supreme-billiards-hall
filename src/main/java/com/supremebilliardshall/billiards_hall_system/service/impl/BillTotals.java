package com.supremebilliardshall.billiards_hall_system.service.impl;

import java.math.BigDecimal;

/**
 * What the customer pays, from the four figures that decide it.
 *
 * <p>This subtraction was written out three times -- in the bill response, in the unsettled
 * strip and in checkout's finalise -- and each copy had to learn about the discount separately
 * when V19 added it. A voucher is the second reduction, and a fourth term in three places is
 * how one of them quietly keeps charging the old total. Same reason RateConversion exists: two
 * callers deriving the same figure slightly differently is not a style problem, it is a bill
 * that disagrees with the receipt.
 *
 * <p>Both reductions are FIXED amounts taken off whatever the bill has come to. Adding a line
 * afterwards raises the subtotals and leaves them where they were put -- the round number
 * agreed at the counter does not stop being round because somebody ordered another beer.
 *
 * <p>Nothing here is clamped at zero. The services refuse to produce a bill of 0.00 in the
 * first place, because payment requires at least 0.01 and a bill nobody can settle would sit on
 * the floor for ever; a negative figure reaching here means one of those refusals has a hole in
 * it, and it should surface as a rejected checkout rather than be rounded away.
 */
final class BillTotals {

    private BillTotals() {
    }

    static BigDecimal payable(BigDecimal subtotalTime, BigDecimal subtotalItems,
                              BigDecimal discountAmount, BigDecimal voucherAmount) {
        return subtotalTime.add(subtotalItems).subtract(discountAmount).subtract(voucherAmount);
    }
}
