package com.supremebilliardshall.billiards_hall_system.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One row of the audit feed, already readable.
 *
 * The id fields are gone. A screen that prints a UUID is asking the reader to do a join in their
 * head, and nobody does — they scroll past it, which makes the log decorative. What the owner
 * needs to see is who, what, to which thing, and why, so that is what this carries.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditFeedEntryDTO {

    private UUID id;
    // AUDIT or STOCK. The screen groups on this; the reader never sees it.
    private String source;
    // The stored constant, kept so the filter has something stable to send back.
    private String action;
    // The same thing in words: "Rate overridden", not "SESSION_RATE_OVERRIDE". On a rate
    // override this is named after the customer type instead — "Happy Hour rate".
    private String actionLabel;
    // What kind of thing changed, in words: "Product", "Table", "Drawer count".
    private String entityLabel;
    // Which one: the product's name, the table's name, the date of the count.
    private String subject;
    private String actorName;
    private String note;
    // Stock rows only: negative took stock out, positive put it in.
    private BigDecimal quantityDelta;
    // The customer type the session was opened on, for rows about a table_session. Joined at
    // read time rather than snapshotted, so it is right for rows written before this existed.
    // Null on every other kind of row, and on a session opened without one.
    private String customerTypeName;
    private Map<String, Object> before;
    private Map<String, Object> after;
    private OffsetDateTime occurredAt;
    private LocalDate businessDate;
}
