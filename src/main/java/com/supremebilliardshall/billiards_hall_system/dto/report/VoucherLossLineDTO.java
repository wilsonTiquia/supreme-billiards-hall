package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * One prize redeemed at the counter.
 *
 * The whole subtraction is shown, like the discount's: what the code was worth, what it
 * actually reached, and what the customer forfeited. A voucher is the one giveaway here where
 * the hall's cost and the customer's benefit are different numbers -- a two-hour code spent on
 * a ninety-minute session costs the hall ninety minutes, and the other thirty are the
 * difference between what was promised and what was used. The owner sizing the next giveaway
 * needs both figures.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherLossLineDTO {

    private UUID billId;
    private Long receiptNo;
    private String code;
    // What the giveaway was -- "October Facebook draw". The only thing that tells one batch
    // from another when the owner is looking at a night's redemptions.
    private String batchNote;
    private Integer voucherMinutes;
    private Integer minutesCovered;
    private Integer minutesForfeited;
    private BigDecimal voucherAmount;
    private String poolTableName;
    private String actorUsername;
    private OffsetDateTime redeemedAt;
}
