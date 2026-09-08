package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.bill.CheckoutPreviewResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.LeaveUnpaidRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.ReceiptResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.UnpaidBillResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleQuoteRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleQuoteResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleRequestDTO;

import java.util.UUID;

public interface CheckoutService {
    // Preview only. Writes nothing.
    CheckoutPreviewResponseDTO previewCheckout(UUID billId);

    // Finalises the bill, takes the one payment, allocates the receipt number under row lock
    // and stores the receipt snapshot — all in one transaction.
    //
    // Also collects a debt: an UNSETTLED bill was finalised when it was left unpaid, so this
    // records the payment against frozen totals and stamps settled_at, leaving closed_at — and
    // therefore the night the sale reports under — exactly where it was.
    PaymentResponseDTO pay(UUID billId, PaymentRequestDTO paymentRequestDTO);

    // Records the sale without the money: the same finalisation a checkout runs — totals frozen,
    // receipt number allocated, closed_at stamped — but status UNSETTLED and no payment. The
    // session must carry a staff note naming who owes it, either already written or supplied here.
    UnpaidBillResponseDTO leaveUnpaid(UUID billId, LeaveUnpaidRequestDTO leaveUnpaidRequestDTO);

    // Finishes a bill that comes to nothing -- a voucher covering the whole of it, or a comped
    // rate -- by closing it with no payment row. Refuses any bill with a figure on it.
    ReceiptResponseDTO settleWithoutPayment(UUID billId);

    ReceiptResponseDTO getReceipt(UUID billId);

    // Prices a set of lines without selling them, so the counter screen can show a total and
    // pay it without ever multiplying a price by a quantity in the browser. Writes nothing.
    QuickSaleQuoteResponseDTO quoteQuickSale(QuickSaleQuoteRequestDTO quickSaleQuoteRequestDTO);

    // A bill with no session, rung up and settled in one transaction.
    PaymentResponseDTO quickSale(QuickSaleRequestDTO quickSaleRequestDTO);
}
