package com.supremebilliardshall.billiards_hall_system.dto.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Only the words. The author is the authenticated user and the kind is always STAFF, both
// decided server-side — a client that sends either is sending a field that does not exist.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionNoteRequestDTO {

    @NotBlank(message = "A note cannot be empty")
    @Size(max = 280, message = "A note must be at most 280 characters")
    private String body;
}
