package com.supremebilliardshall.billiards_hall_system.dto.time;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServerTimeResponseDTO {

    private OffsetDateTime serverNow;
}
