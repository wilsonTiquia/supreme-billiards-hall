package com.supremebilliardshall.billiards_hall_system.dto.session;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.supremebilliardshall.billiards_hall_system.entity.RateOverrideKind;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

// No start time and no duration: the server is the only clock.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpenSessionRequestDTO {

    @NotNull(message = "Table is required")
    private UUID tableId;

    @NotNull(message = "Customer type is required")
    private UUID customerTypeId;

    // The friend rate. Any value, no floor and no approval step, but only for a customer
    // type that allows it. Absent means bill at the table's standard rate.
    @DecimalMin(value = "0.0000", message = "Rate override must not be negative")
    private BigDecimal rateOverridePerMinute;

    // The same friend rate in the unit the owner thinks in. The service divides it by 60 to get
    // the rate that bills and stores both; money is never computed from this figure.
    @DecimalMin(value = "0.00", message = "Rate override must not be negative")
    private BigDecimal rateOverridePerHour;

    private String rateOverrideReason;

    // Which kind of override the rate above is. PROMO is happy hour — an event, so it is NOT
    // gated on the customer type, exactly as the flat rate is not; FRIEND is a favour and keeps
    // its allowsRateOverride gate. Absent alongside a rate means FRIEND, which is what every
    // override written before this field existed was.
    private RateOverrideKind rateOverrideKind;

    // Tournament pricing: a fixed charge for the whole session however long it runs. NOT gated
    // on the customer type — a tournament is an event, not a kind of customer, and requiring a
    // "Tournament" customer type would reintroduce the thing to remember to switch back that
    // setting this per session exists to remove. The control is the reason and the actor.
    @DecimalMin(value = "0.00", message = "Flat amount must not be negative")
    private BigDecimal flatAmount;

    private String flatRateReason;

    // AT MOST one, not exactly one — unlike the table rate, where a rate is mandatory. Neither
    // is the ordinary case and means "charge the standard rate"; both is the client asking for
    // two different giveaways at once, which has no sensible reading.
    @JsonIgnore
    @AssertTrue(message = "Give a friend rate per minute or per hour, not both")
    public boolean isOnlyOneOverrideGiven() {
        return rateOverridePerMinute == null || rateOverridePerHour == null;
    }

    // One session, one pricing story. The database says so too, but answered here as well
    // because a constraint violation surfaces as a 409 — the right answer to a conflict with
    // existing state, and the wrong one to a request that was malformed before it was sent.
    @JsonIgnore
    @AssertTrue(message = "A session is priced at a friend rate or a flat amount, not both")
    public boolean isOnlyOnePricingGiven() {
        return flatAmount == null
                || (rateOverridePerMinute == null && rateOverridePerHour == null);
    }

    // A kind labels an override; it does not create one. Without a rate beside it there is
    // nothing to label — the session bills at the table's standard rate and calling that a
    // promo would put a discount in the report that nobody was ever given. The database says so
    // too, in table_session_override_kind_together_chk, but a request that was malformed before
    // it was sent is a 400 rather than the 409 a constraint violation surfaces as.
    @JsonIgnore
    @AssertTrue(message = "A rate override kind needs a rate to go with it")
    public boolean isOverrideKindGivenWithARate() {
        return rateOverrideKind == null
                || rateOverridePerMinute != null || rateOverridePerHour != null;
    }

    /*
     * A PROMO needs a reason; a FRIEND rate does not. That asymmetry is deliberate.
     *
     * The whole point of the promo is measuring it — an unlabelled happy hour is unmeasurable,
     * and this matches the flat rate, which already requires one for the same reason. The
     * friend rate's reason stays optional as it has always been. Named here rather than
     * quietly made symmetrical: making friend rates require a reason too is a change to how the
     * counter works tonight, and it is not this change.
     */
    @JsonIgnore
    @AssertTrue(message = "A promo needs a reason")
    public boolean isPromoReasonGiven() {
        return rateOverrideKind != RateOverrideKind.PROMO
                || (rateOverrideReason != null && !rateOverrideReason.isBlank());
    }

    // The reason is the whole control on a fee somebody chose, so it is not optional. Zero is
    // still allowed — a comped tournament table — which is exactly why the reason has to be
    // there: without it the row is a smaller number with nobody's name on it.
    @JsonIgnore
    @AssertTrue(message = "A flat rate needs a reason")
    public boolean isFlatRateReasonGiven() {
        return flatAmount == null || (flatRateReason != null && !flatRateReason.isBlank());
    }
}
