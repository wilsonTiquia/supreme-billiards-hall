package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.session.SessionNoteRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.SessionNoteResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.entity.Bill;
import com.supremebilliardshall.billiards_hall_system.entity.Payment;
import com.supremebilliardshall.billiards_hall_system.entity.SessionNote;
import com.supremebilliardshall.billiards_hall_system.entity.SessionNoteKind;
import com.supremebilliardshall.billiards_hall_system.entity.TableSession;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BillRepository;
import com.supremebilliardshall.billiards_hall_system.repository.SessionNoteRepository;
import com.supremebilliardshall.billiards_hall_system.repository.TableSessionRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.SessionNoteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class SessionNoteServiceImpl implements SessionNoteService {

    private final SessionNoteRepository sessionNoteRepository;
    private final TableSessionRepository tableSessionRepository;
    private final BillRepository billRepository;
    private final AppUserRepository appUserRepository;
    private final BranchContext branchContext;

    public SessionNoteServiceImpl(SessionNoteRepository sessionNoteRepository,
                                  TableSessionRepository tableSessionRepository,
                                  BillRepository billRepository,
                                  AppUserRepository appUserRepository,
                                  BranchContext branchContext) {
        this.sessionNoteRepository = sessionNoteRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.billRepository = billRepository;
        this.appUserRepository = appUserRepository;
        this.branchContext = branchContext;
    }


    @Override
    @Transactional
    public SessionNoteResponseDTO addNote(UUID sessionId, SessionNoteRequestDTO sessionNoteRequestDTO) {
        // Branch-scoped, so another branch's session is a 404 rather than a writable target.
        TableSession session = tableSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));

        // No status check on purpose. A closed session still takes notes: staff forget during
        // a busy shift and add the name when they see the unpaid card. Append-only is what
        // makes that safe — a late note cannot overwrite an earlier one.
        SessionNote note = write(session, SessionNoteKind.STAFF,
                sessionNoteRequestDTO.getBody().trim(), branchContext.getCurrentUserId());

        return toResponseDto(note, usernames(List.of(note)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionNoteResponseDTO> getNotesForSession(UUID sessionId) {
        if (!tableSessionRepository.existsById(sessionId)) {
            throw new ResourceNotFoundException("Session", sessionId);
        }
        return toResponseDtos(sessionNoteRepository.findBySessionId(sessionId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionNoteResponseDTO> getNotesForBill(UUID billId) {
        if (!billRepository.existsById(billId)) {
            throw new ResourceNotFoundException("Bill", billId);
        }
        return toResponseDtos(sessionNoteRepository.findByBillId(billId));
    }

    @Override
    @Transactional(readOnly = true)
    public SessionNoteResponseDTO getLatestNoteForBill(UUID billId) {
        List<SessionNote> notes = sessionNoteRepository.findByBillId(billId);
        if (notes.isEmpty()) {
            return null;
        }
        // The thread is oldest first, so the most recent is the last of it.
        SessionNote latest = notes.getLast();
        return toResponseDto(latest, usernames(List.of(latest)));
    }

    @Override
    // MANDATORY, for the reason AuditService.record gives: a note recording a payment that
    // then rolled back would be a lie in the one record that is never corrected by deletion.
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSettlement(Payment payment) {
        Bill bill = billRepository.findById(payment.getBillId())
                .orElseThrow(() -> new ResourceNotFoundException("Bill", payment.getBillId()));

        // A quick sale carries no session, and so no thread: it is created and settled in one
        // transaction, and there is never a debt to attribute.
        TableSession session = tableSessionRepository.findByBillId(bill.getId()).stream()
                .max(Comparator.comparing(TableSession::getOpenedAt))
                .orElse(null);
        if (session == null) {
            return;
        }

        // Written against the most recent session rather than all of them: a merged bill has
        // several, and one settlement is one event, not one per table.
        write(session, SessionNoteKind.SYSTEM, settlementBody(payment), payment.getTakenBy());
    }


    private SessionNote write(TableSession session, SessionNoteKind kind, String body, UUID authorId) {
        SessionNote note = new SessionNote();
        note.setBranchId(session.getBranchId());
        note.setSessionId(session.getId());
        note.setKind(kind);
        note.setBody(body);
        note.setAuthorId(authorId);
        // Flushed so session_note_body_chk answers here rather than at commit, and so
        // created_at comes back for the response.
        return sessionNoteRepository.saveAndFlush(note);
    }

    // Composed here, never typed: the kind is what proves it, but the wording should match
    // what a reader expects a settlement to say.
    private String settlementBody(Payment payment) {
        return "Settled by " + username(payment.getTakenBy())
                + " — " + payment.getMethod().name()
                + " " + payment.getAmount().toPlainString();
    }

    private List<SessionNoteResponseDTO> toResponseDtos(List<SessionNote> notes) {
        Map<UUID, String> usernames = usernames(notes);
        return notes.stream().map(note -> toResponseDto(note, usernames)).toList();
    }

    private SessionNoteResponseDTO toResponseDto(SessionNote note, Map<UUID, String> usernames) {
        return new SessionNoteResponseDTO(
                note.getId(),
                note.getSessionId(),
                note.getKind(),
                note.getBody(),
                note.getAuthorId(),
                usernames.get(note.getAuthorId()),
                note.getCreatedAt());
    }

    // One lookup for the authors on this thread, rather than one per note.
    private Map<UUID, String> usernames(List<SessionNote> notes) {
        List<UUID> authorIds = notes.stream()
                .map(SessionNote::getAuthorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, String> usernames = new HashMap<>();
        if (!authorIds.isEmpty()) {
            for (AppUser user : appUserRepository.findAllById(authorIds)) {
                usernames.put(user.getId(), user.getUsername());
            }
        }
        return usernames;
    }

    private String username(UUID userId) {
        return appUserRepository.findById(userId)
                .map(AppUser::getUsername)
                .orElse("unknown");
    }
}
