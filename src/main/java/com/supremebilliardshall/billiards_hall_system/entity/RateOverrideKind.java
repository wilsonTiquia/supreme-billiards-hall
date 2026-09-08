package com.supremebilliardshall.billiards_hall_system.entity;

// Mirrors the Postgres enum type rate_override_kind.
//
// A pricing mode, not a kind of customer: PROMO is happy hour, ungated and allowed on any
// customer type; FRIEND is a favour, gated on customer_type.allows_rate_override. Neither
// changes how the charge is computed — see V18.
public enum RateOverrideKind {
    FRIEND,
    PROMO
}
