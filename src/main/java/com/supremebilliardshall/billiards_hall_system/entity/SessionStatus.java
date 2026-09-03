package com.supremebilliardshall.billiards_hall_system.entity;

// Mirrors the Postgres enum type session_status.
public enum SessionStatus {
    OPEN,
    PAUSED,
    CLOSED,
    AUTO_CLOSED,
    VOIDED
}
