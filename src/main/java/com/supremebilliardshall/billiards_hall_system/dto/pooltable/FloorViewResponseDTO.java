package com.supremebilliardshall.billiards_hall_system.dto.pooltable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

// The floor view carries the server clock with it, so the timer on screen needs no second
// round trip and never drifts onto the browser's clock.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FloorViewResponseDTO {

    private List<PoolTableResponseDTO> tables;
    private OffsetDateTime serverNow;
}
