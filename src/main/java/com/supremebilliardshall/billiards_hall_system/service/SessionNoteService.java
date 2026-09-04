package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.session.SessionNoteRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.SessionNoteResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.Payment;

import java.util.List;
import java.util.UUID;

/*
 * Notes attaching people to a table, and therefore to its bill.
 *
 * Created and listed, never updated and never deleted: the table is append-only in the
 * database too. A note about who owes money that an employee can quietly remove is worse than
 * no note at all, so a wrong one is corrected by a later one.
 */
public interface SessionNoteService {

    // A note can be added to a session in any state. Staff forget during a busy shift and
    // want to put the name on it when they see the unpaid card, which is after the close.
    SessionNoteResponseDTO addNote(UUID sessionId, SessionNoteRequestDTO sessionNoteRequestDTO);

    List<SessionNoteResponseDTO> getNotesForSession(UUID sessionId);

    // Every note on every session that bill carried, oldest first. This is the thread that
    // survives settlement: the bill leaves the unpaid strip and its notes travel with it.
    List<SessionNoteResponseDTO> getNotesForBill(UUID billId);

    // The one the floor card shows, so an unpaid bill reads "Table 1 · ₱1,350.00 · Marco + 2"
    // rather than as an anonymous amount. Null when nobody has written one yet.
    SessionNoteResponseDTO getLatestNoteForBill(UUID billId);

    // The settlement note, so the thread is the whole story of the debt in one place: who
    // collected, when, and by what method. Joins the checkout's transaction — a note claiming
    // a payment that rolled back would be a lie in the record.
    void recordSettlement(Payment payment);
}
