package com.supremebilliardshall.billiards_hall_system.dto.voucher;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/*
 * One run of codes and what became of them.
 *
 * The four counts are exclusive and sum to `issued`, which is what makes the row readable at a
 * glance: every code is either spent, run out of time, or still out there. `outstanding` is the
 * figure the owner is actually asking for -- what could still walk through the door.
 *
 * `codes` is populated only in the response to generating a batch, where the owner needs them
 * to copy or print. The list endpoint leaves it null: a screen that renders every code of every
 * batch is a screen that leaks the whole giveaway to anyone who can see it over a shoulder.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherBatchResponseDTO {

    private UUID id;
    // Minutes is what is stored; hours is what the owner typed, given back so the screen reads
    // in the same units the form does. Nothing computes from it.
    private Integer minutes;
    private String hoursLabel;
    private Integer quantity;
    private LocalDate expiresOn;
    private String note;
    private String createdByUsername;
    private OffsetDateTime createdAt;

    private long issued;
    private long redeemed;
    private long expired;
    private long outstanding;

    private List<VoucherResponseDTO> codes;
}
