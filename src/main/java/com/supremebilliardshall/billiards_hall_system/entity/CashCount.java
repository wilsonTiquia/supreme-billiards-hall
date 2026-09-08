package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// The one control that survives from the hall's paper process: count the drawer, compare it
// to what the system says should be there. One row per branch per business day.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cash_count")
public class CashCount implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    // SUM(payment.amount) for CASH on this business date, frozen at the moment of counting
    // and always computed by the server. Takings only — the drawer also holds the float.
    @Column(name = "cash_sales", nullable = false, precision = 12, scale = 2)
    private BigDecimal cashSales;

    // The change float as applied to THIS night, copied from the branch standard rather than
    // joined to it: the standard can change next month, and a night reconciled in March has to
    // still add up in March's terms.
    //
    // DELIBERATELY NOT DEFAULTED, unlike cashExpenses below. The two look alike and are not.
    // Zero is a true answer for cash_expenses — a night nobody paid anything out genuinely had
    // none — but it is never a true answer here: this figure comes from standard_cash_float and
    // a hall that keeps a float kept it whether or not anyone set the field. Defaulting it would
    // turn a loud NOT NULL violation into a variance that is quietly wrong by the size of the
    // float, on every night the caller forgot. Failing at the insert is the correct behaviour.
    @Column(name = "opening_float", nullable = false, precision = 12, scale = 2)
    private BigDecimal openingFloat;

    // True when this night ran something other than the branch standard. The why is on the
    // audit row; this exists so "which nights were unusual" is answerable from the counts.
    @Column(name = "float_overridden", nullable = false)
    private boolean floatOverridden;

    // Cash paid out of the till on this night — a water delivery, a bag of ice — frozen at the
    // moment of counting like the two figures above it. Money that left the drawer is money the
    // drawer should not be expected to hold. Cash only: rent paid by transfer never touched it.
    //
    // Defaulted here rather than relying on the column's DEFAULT 0: Hibernate names every mapped
    // column in the INSERT, so an unset field sends an explicit NULL and the database default
    // never applies. Zero is defensible for this column specifically because it is the honest
    // answer for a night nothing was paid out on — see openingFloat above for why the same
    // treatment would be wrong there.
    @Column(name = "cash_expenses", nullable = false, precision = 12, scale = 2)
    private BigDecimal cashExpenses = BigDecimal.ZERO;

    @Column(name = "counted_cash", nullable = false, precision = 12, scale = 2)
    private BigDecimal countedCash;

    // Generated column: counted_cash - (cash_sales + opening_float - cash_expenses). Negative
    // is a shortfall.
    // Read back on UPDATE as well as INSERT: an admin correcting a mistyped count changes
    // counted_cash, and without this the variance in the response would be the old one.
    @Generated(event = { EventType.INSERT, EventType.UPDATE })
    @Column(name = "variance", insertable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal variance;

    /*
     * Who counted the drawer, and when.
     *
     * Both writable, because a recount IS a new count: it re-reads the takings and takes a
     * fresh figure, so leaving these at the first count would have the row claim the new
     * variance was measured at a time it was not. They were updatable = false, which silently
     * swallowed the setCountedBy already in recountAfterClose -- a recounted night kept naming
     * whoever counted it first.
     *
     * counted_at carried @CreationTimestamp, which is a claim about the whole lifecycle of a
     * column: written once, at insert, never again. That was never true here -- this column's
     * lifecycle is "stamped every time the drawer is counted" -- and Hibernate honours the
     * claim by leaving the column out of every UPDATE, so dropping updatable = false did
     * nothing on its own. The service stamps it now, which is how every other event timestamp
     * in this codebase already works: closedAt, unsettledAt, settledAt, discountAt and
     * redeemedAt are all set by the service that owns the event.
     *
     * The previous pair is not lost: CASH_COUNT_SUPERSEDED carries countedAt and the original
     * figures, which is this project's rule for anything a correction overwrites.
     */
    @Column(name = "counted_by", nullable = false)
    private UUID countedBy;

    @Column(name = "counted_at", nullable = false)
    private OffsetDateTime countedAt;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    // Set together, never separately — the database enforces that with a check constraint.
    // Null here means the drawer is counted but the night is not yet closed.
    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;
}
