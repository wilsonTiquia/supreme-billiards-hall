package com.supremebilliardshall.billiards_hall_system.dto.voucher;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedeemVoucherRequestDTO {

    /*
     * As typed. The server normalises -- uppercase, spaces and dashes removed, prefix added
     * back if it was left off -- so the cashier can type what they see and not what is stored.
     *
     * Bounded generously rather than tightly: an exact length here would answer "how long is a
     * code?" to anyone probing the endpoint, and it would turn a stray keystroke into a 400
     * whose message is about validation instead of the plain "no voucher with that code" the
     * counter needs while a queue waits.
     */
    @NotBlank(message = "A voucher code is required")
    @Size(max = 40, message = "That is not a voucher code")
    private String code;
}
