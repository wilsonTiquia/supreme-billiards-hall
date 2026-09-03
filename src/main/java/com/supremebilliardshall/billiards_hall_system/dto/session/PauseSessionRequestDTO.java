package com.supremebilliardshall.billiards_hall_system.dto.session;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PauseSessionRequestDTO {

    @Size(max = 255, message = "Reason must be at most 255 characters")
    private String reason;
}
