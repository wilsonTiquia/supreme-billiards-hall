package com.supremebilliardshall.billiards_hall_system.dto.voucher;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * One code, as the owner sees it. ADMIN only, and that is a security boundary rather than a
 * layout choice: a staff member who can read an unredeemed code can redeem it.
 *
 * `code` is the display form -- SB-7K4-M2Q -- and never what is stored. The two are different
 * strings and only one of them is ever matched.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherResponseDTO {

    private UUID id;
    private UUID batchId;
    private String code;
    private Integer minutes;
    private LocalDate expiresOn;

    // OUTSTANDING, REDEEMED or EXPIRED. Resolved against today rather than stored: a code
    // expiring tomorrow is outstanding today and expired the day after, with nothing having
    // written to the row.
    private String status;

    private OffsetDateTime redeemedAt;
    private String redeemedByUsername;
    private UUID redeemedBillId;
    private Long redeemedReceiptNo;
}
