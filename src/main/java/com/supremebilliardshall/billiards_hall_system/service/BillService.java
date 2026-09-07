package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.PagedResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.AddBillLineRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.AddBillLineResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.BillLineResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.BillResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.BillSummaryResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.UnpaidBillResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.UnsettledBillResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.VoidBillLineRequestDTO;

import java.time.LocalDate;
import java.util.List;

import java.util.UUID;

public interface BillService {
    BillResponseDTO getBill(UUID id);

    // Snapshots name, selling_price and avg_cost, then moves stock in the same transaction.
    AddBillLineResponseDTO addLine(UUID billId, AddBillLineRequestDTO addBillLineRequestDTO);

    // Marks the line voided and retained, and returns the stock with a SALE_VOID movement.
    BillLineResponseDTO voidLine(UUID billId, UUID lineId, VoidBillLineRequestDTO voidBillLineRequestDTO);

    // Open bills on the current business day with no live session, for the floor header. A
    // session can close without being paid and the table then reads free; without this the
    // bill cannot be found again.
    List<UnsettledBillResponseDTO> getUnsettledBills();

    // The debt list: bills deliberately left unpaid, newest first, across every business date.
    // A different question from getUnsettledBills — that one finds bills nobody remembered to
    // check out, this one finds money the hall agreed to wait for.
    List<UnpaidBillResponseDTO> getUnpaidBills();

    UnpaidBillResponseDTO getUnpaidBill(UUID billId);

    // ADMIN. One business day's settled sales, newest first, so the owner can find a receipt
    // without a database client.
    PagedResponseDTO<BillSummaryResponseDTO> getSettledBills(LocalDate businessDate, int page, int size);
}
