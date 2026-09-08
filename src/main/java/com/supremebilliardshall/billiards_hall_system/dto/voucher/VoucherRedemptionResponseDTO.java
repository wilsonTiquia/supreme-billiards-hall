package com.supremebilliardshall.billiards_hall_system.dto.voucher;

import com.supremebilliardshall.billiards_hall_system.dto.bill.BillResponseDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/*
 * What just happened, in the words the cashier has to say out loud.
 *
 * The bill comes back whole so the screen re-renders from one response, and the redemption
 * facts sit beside it rather than being dug out of it. `minutesForfeited` is the field this
 * type exists for: the customer with a two-hour voucher who played ninety minutes has thirty
 * minutes taken off them, and the moment to say so is at the counter, not in an acceptance
 * test. Nobody reads a receipt and works out what they lost.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherRedemptionResponseDTO {

    private BillResponseDTO bill;

    private String code;
    // What the code was worth, and what it actually reached. The first is the prize as
    // advertised; the second is bounded by the time on the bill.
    private Integer voucherMinutes;
    private Integer minutesCovered;
    // The difference, stated rather than left to be worked out. Zero on a bill that ran longer
    // than the voucher, which is the ordinary case.
    private Integer minutesForfeited;
    private BigDecimal voucherAmount;
}
