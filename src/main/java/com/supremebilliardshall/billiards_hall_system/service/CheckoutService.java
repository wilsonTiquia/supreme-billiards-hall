package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.bill.CheckoutPreviewResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.ReceiptResponseDTO;
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
    PaymentResponseDTO pay(UUID billId, PaymentRequestDTO paymentRequestDTO);

    ReceiptResponseDTO getReceipt(UUID billId);

    // Prices a set of lines without selling them, so the counter screen can show a total and
    // pay it without ever multiplying a price by a quantity in the browser. Writes nothing.
    QuickSaleQuoteResponseDTO quoteQuickSale(QuickSaleQuoteRequestDTO quickSaleQuoteRequestDTO);

    // A bill with no session, rung up and settled in one transaction.
    PaymentResponseDTO quickSale(QuickSaleRequestDTO quickSaleRequestDTO);
}
