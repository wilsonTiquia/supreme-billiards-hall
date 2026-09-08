package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.voucher.RedeemVoucherRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.voucher.VoucherBatchRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.voucher.VoucherBatchResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.voucher.VoucherRedemptionResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.voucher.VoucherResponseDTO;

import java.util.List;
import java.util.UUID;

public interface VoucherService {

    // Generates the whole batch in one transaction and returns the codes for the owner to copy
    // or print. ADMIN: the codes are the giveaway, and whoever can read them can spend them.
    VoucherBatchResponseDTO createBatch(VoucherBatchRequestDTO voucherBatchRequestDTO);

    // Every batch with its issued / redeemed / expired / outstanding counts. No codes.
    List<VoucherBatchResponseDTO> getBatches();

    // The individual codes. Both filters optional; status is OUTSTANDING, REDEEMED or EXPIRED.
    List<VoucherResponseDTO> getVouchers(UUID batchId, String status);

    /*
     * Spends a code against a bill, covering up to its minutes of that bill's table time.
     *
     * Counter work, not admin: this is the last step before payment. Single use is enforced by
     * a conditional UPDATE rather than by a pre-check -- two tills racing one code is the case
     * a read-then-write loses.
     */
    VoucherRedemptionResponseDTO redeem(UUID billId, RedeemVoucherRequestDTO redeemVoucherRequestDTO);

    // Un-redeems the voucher on this bill, for a code entered against the wrong one. Returns
    // the code to unredeemed and the bill to its full amount. Audited, like the redemption.
    VoucherRedemptionResponseDTO release(UUID billId);
}
