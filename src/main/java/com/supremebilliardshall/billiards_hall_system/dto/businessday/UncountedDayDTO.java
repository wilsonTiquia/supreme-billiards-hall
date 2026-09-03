package com.supremebilliardshall.billiards_hall_system.dto.businessday;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

// A night that traded and was never counted. It carries the date and how many bills it holds,
// and deliberately NOT the cash figure: expected cash is not readable before counting, and
// putting it on a strip anyone can see would defeat the blind count.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UncountedDayDTO {

    private LocalDate businessDate;
    private int bills;
}
