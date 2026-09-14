package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

// Category x month for the six months ending in the month `to` falls in, so a rising
// electricity bill is a row the eye can follow rather than one number. The last column runs
// only to `to`, and is partial when `to` is not a month end.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseMonthGridDTO {

    // "YYYY-MM", six of them, oldest first. The columns of every row below.
    private List<String> months;
    private List<ExpenseMonthRowDTO> rows;
}
