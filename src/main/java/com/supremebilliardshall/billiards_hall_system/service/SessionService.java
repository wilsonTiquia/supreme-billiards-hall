package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.pooltable.TableSessionSummaryDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.OpenSessionRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.PauseSessionRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.SessionResponseDTO;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface SessionService {
    /*
     * Charges less table time than was played — the friend who gets two hours off a three-hour
     * session. Allowed, but attributable: a reason is required, the actor is recorded, and the
     * reduction shows up beside comps and friend rates in the losses drill-down.
     *
     * It may only go DOWN. Charging for time that was not played is an overcharge, not a
     * discount, and there is no case for it.
     *
     * session_segment and the computed billed_minutes are untouched: what happened at the
     * table stays on the record, and only what is CHARGED changes.
     */
    com.supremebilliardshall.billiards_hall_system.dto.session.SessionResponseDTO overrideBilledMinutes(
            java.util.UUID sessionId,
            com.supremebilliardshall.billiards_hall_system.dto.session.BilledMinutesOverrideRequestDTO request);

    // Creates the bill, the session and its first segment in one transaction.
    SessionResponseDTO openSession(OpenSessionRequestDTO openSessionRequestDTO);

    SessionResponseDTO getSession(UUID id);

    SessionResponseDTO pauseSession(UUID id, PauseSessionRequestDTO pauseSessionRequestDTO);

    SessionResponseDTO resumeSession(UUID id);

    // Ends the open segments, computes billed minutes and writes one TIME line per segment.
    SessionResponseDTO closeSession(UUID id);

    // Live sessions keyed by the table they occupy, for the floor view.
    Map<UUID, TableSessionSummaryDTO> getLiveSessionSummariesByTable();

    // The sessions attached to one bill, for the bill view.
    List<TableSessionSummaryDTO> getSummariesForBill(UUID billId);

    // The end-of-day safety net. Closes every live session capped at the business-day end,
    // marked AUTO_END_OF_DAY and needs_review. Takes the cutoff so it can be tested without
    // waiting for 5 AM.
    List<SessionResponseDTO> autoCloseOpenSessions(java.time.OffsetDateTime cutoff);
}
