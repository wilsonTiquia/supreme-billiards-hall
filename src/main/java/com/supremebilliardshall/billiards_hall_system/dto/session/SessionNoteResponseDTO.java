package com.supremebilliardshall.billiards_hall_system.dto.session;

import com.supremebilliardshall.billiards_hall_system.entity.SessionNoteKind;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

// Carries no money and no cost, so one shape serves both roles.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionNoteResponseDTO {

    private UUID id;
    private UUID sessionId;
    // STAFF or SYSTEM. The client marks the two apart rather than trusting the words: a
    // settlement note is written by the server, and typing the same sentence must not pass
    // for one.
    private SessionNoteKind kind;
    private String body;
    private UUID authorId;
    private String authorUsername;
    private OffsetDateTime createdAt;
}
