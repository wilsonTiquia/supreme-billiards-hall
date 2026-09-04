package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

// Who was on the table. Append-only: session_note_append_only rejects UPDATE and DELETE, so
// every column here is updatable = false and nothing in the application ever writes twice.
// A wrong note is corrected by a later note.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "session_note")
public class SessionNote implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    // columnDefinition names the Postgres enum type, as on table_session.status: without it
    // Hibernate builds the cast from the Java class name and emits 'STAFF'::SessionNoteKind.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "kind", nullable = false, updatable = false, columnDefinition = "session_note_kind")
    private SessionNoteKind kind;

    @Column(name = "body", nullable = false, updatable = false, columnDefinition = "text")
    private String body;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;
}
