package com.supremebilliardshall.billiards_hall_system.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pesos per hour to the rate that bills.
 *
 * The owner configures both the table rate and the friend rate in pesos per hour and kept
 * mistyping the division. This is that division, in one place, done the same way for both --
 * two services deriving the same figure by slightly different rounding is how a table rate and
 * a giveaway on the same table stop reconciling.
 *
 * Nothing here is a price. The per-minute figure it returns is what gets stored and billed; the
 * hourly figure the admin typed is recorded alongside it for the screen to read back, and
 * nothing computes money from that.
 */
final class RateConversion {

    private RateConversion() {
    }

    /**
     * The rate that bills, from whichever figure was typed.
     *
     * <p>HALF_UP to four decimals, the scale of every rate column. The division is not always
     * exact: PHP 200/hour is 3.3333/min, which prices an hour at PHP 199.9980 rather than
     * PHP 200. That gap is reported rather than smoothed over -- see
     * {@link #effectivePerHour(BigDecimal)}.
     *
     * <p>Both null returns null, which is the friend rate's "no override, charge the standard"
     * case. The table rate cannot reach it: PoolTableRateRequestDTO answers 400 to a request
     * carrying neither figure.
     */
    static BigDecimal perMinuteFrom(BigDecimal perMinute, BigDecimal perHour) {
        if (perMinute != null) {
            return perMinute;
        }
        if (perHour == null) {
            return null;
        }
        return perHour.divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
    }

    /**
     * What an hour is actually priced at, as against what was typed.
     *
     * <p>Computed here and not in the browser: 60 x a rate is arithmetic on money.
     */
    static BigDecimal effectivePerHour(BigDecimal perMinute) {
        return perMinute == null ? null : perMinute.multiply(BigDecimal.valueOf(60));
    }
}
