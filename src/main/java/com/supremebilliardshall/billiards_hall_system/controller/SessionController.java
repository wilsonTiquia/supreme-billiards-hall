package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.session.BilledMinutesOverrideRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.OpenSessionRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.PauseSessionRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.SessionNoteRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.SessionNoteResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.SessionResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.SessionNoteService;
import com.supremebilliardshall.billiards_hall_system.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// Opening and closing tables is the counter's job, so these are not admin-only. No endpoint
// here accepts a duration or an amount: the server is the only clock.
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final SessionNoteService sessionNoteService;

    public SessionController(SessionService sessionService,
                             SessionNoteService sessionNoteService) {
        this.sessionService = sessionService;
        this.sessionNoteService = sessionNoteService;
    }

    @PostMapping
    public ResponseEntity<APIResponse<SessionResponseDTO>> openSession(@Valid @RequestBody OpenSessionRequestDTO openSessionRequestDTO) {
        SessionResponseDTO openedSession = sessionService.openSession(openSessionRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        openedSession,
                        "Session opened successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<APIResponse<SessionResponseDTO>> getSession(@PathVariable UUID id) {
        SessionResponseDTO session = sessionService.getSession(id);
        return ResponseEntity.
                ok(APIResponse.success(
                        session,
                        "Session fetched successfully"));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<APIResponse<SessionResponseDTO>> pauseSession(@PathVariable UUID id,
                                                                       @Valid @RequestBody(required = false) PauseSessionRequestDTO pauseSessionRequestDTO) {
        SessionResponseDTO pausedSession = sessionService.pauseSession(id, pauseSessionRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        pausedSession,
                        "Session paused successfully"));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<APIResponse<SessionResponseDTO>> resumeSession(@PathVariable UUID id) {
        SessionResponseDTO resumedSession = sessionService.resumeSession(id);
        return ResponseEntity.
                ok(APIResponse.success(
                        resumedSession,
                        "Session resumed successfully"));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<APIResponse<SessionResponseDTO>> closeSession(@PathVariable UUID id) {
        SessionResponseDTO closedSession = sessionService.closeSession(id);
        return ResponseEntity.
                ok(APIResponse.success(
                        closedSession,
                        "Session closed successfully"));
    }


    // Charging less table time than was played. Taken at checkout, before payment, and only
    // downwards — see SessionService.overrideBilledMinutes.
    @PostMapping("/{id}/billed-minutes")
    public ResponseEntity<APIResponse<SessionResponseDTO>> overrideBilledMinutes(
            @PathVariable UUID id,
            @Valid @RequestBody BilledMinutesOverrideRequestDTO billedMinutesOverrideRequestDTO) {
        SessionResponseDTO session = sessionService.overrideBilledMinutes(id, billedMinutesOverrideRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        session,
                        "Time charged reduced"));
    }

    // Who is on the table. Not admin-only, and deliberately so: the counter is who knows the
    // names, and a note they cannot write is a note nobody writes.
    @GetMapping("/{id}/notes")
    public ResponseEntity<APIResponse<List<SessionNoteResponseDTO>>> getNotes(@PathVariable UUID id) {
        List<SessionNoteResponseDTO> notes = sessionNoteService.getNotesForSession(id);
        return ResponseEntity.
                ok(APIResponse.success(
                        notes,
                        "Session notes fetched successfully"));
    }

    // Append-only: there is no PUT and no DELETE here, and there will not be one. A wrong
    // note is corrected by a later note. The author is the authenticated user, never the body.
    @PostMapping("/{id}/notes")
    public ResponseEntity<APIResponse<SessionNoteResponseDTO>> addNote(@PathVariable UUID id,
                                                                      @Valid @RequestBody SessionNoteRequestDTO sessionNoteRequestDTO) {
        SessionNoteResponseDTO note = sessionNoteService.addNote(id, sessionNoteRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        note,
                        "Note added successfully"));
    }

}
