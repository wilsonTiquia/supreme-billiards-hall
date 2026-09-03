package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleQuoteRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleQuoteResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleRequestDTO;
import com.supremebilliardshall.billiards_hall_system.service.CheckoutService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// A sale with no table: rung up and settled in one transaction.
@RestController
@RequestMapping("/api/v1/quick-sales")
public class QuickSaleController {

    private final CheckoutService checkoutService;

    public QuickSaleController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    // Prices the lines without selling them, so the counter never has to work out the amount
    // it is about to charge. POST because it carries a body; reading it writes nothing.
    @PostMapping("/quote")
    public ResponseEntity<APIResponse<QuickSaleQuoteResponseDTO>> quoteQuickSale(@Valid @RequestBody QuickSaleQuoteRequestDTO quickSaleQuoteRequestDTO) {
        QuickSaleQuoteResponseDTO quote = checkoutService.quoteQuickSale(quickSaleQuoteRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        quote,
                        "Quick sale quoted successfully"));
    }

    @PostMapping
    public ResponseEntity<APIResponse<PaymentResponseDTO>> quickSale(@Valid @RequestBody QuickSaleRequestDTO quickSaleRequestDTO) {
        PaymentResponseDTO payment = checkoutService.quickSale(quickSaleRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        payment,
                        "Quick sale recorded successfully"));
    }

}
