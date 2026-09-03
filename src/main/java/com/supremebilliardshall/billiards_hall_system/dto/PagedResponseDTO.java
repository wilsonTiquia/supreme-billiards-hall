package com.supremebilliardshall.billiards_hall_system.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

// A stable paging envelope. Spring's Page serialises with an unstable shape, so the contract
// the frontend codes against is defined here instead.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponseDTO<T> {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
