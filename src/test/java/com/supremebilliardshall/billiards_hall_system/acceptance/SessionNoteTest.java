package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Who owes the money. The unpaid strip could show a table, a time and an amount, so three
// unpaid bills on Table 1 read identically the next morning; a note supplies the name.
//
// The rules that matter here are the ones that make the note worth trusting: it cannot be
// edited, it cannot be deleted, its author cannot be chosen by the client, and it cannot be
// read across a branch boundary.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SessionNoteTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PoolTableRepository poolTableRepository;

    @Autowired
    private PoolTableRateRepository poolTableRateRepository;

    @Autowired
    private CustomerTypeRepository customerTypeRepository;

    @Autowired
    private SessionSegmentRepository sessionSegmentRepository;

    @Autowired
    private SessionNoteRepository sessionNoteRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private UUID branchId;
    private UUID userId;
    private UUID tableId;
    private UUID customerTypeId;

    // A second hall, with its own everything. Nothing of this branch's may ever be reachable
    // through a principal bound to the first.
    private UUID otherBranchId;
    private UUID otherUserId;
    private UUID otherTableId;
    private UUID otherCustomerTypeId;

    @BeforeEach
    void setUp() {
        branchId = branch("NOTES");
        userId = employee(branchId, "note-tester");
        tableId = table(branchId, "Table 1", 1);
        customerTypeId = customerType(branchId);

        otherBranchId = branch("NOTES-B");
        otherUserId = employee(otherBranchId, "other-note-tester");
        otherTableId = table(otherBranchId, "Table 1", 1);
        otherCustomerTypeId = customerType(otherBranchId);
    }

    // The whole point of the feature: the floor card can name who owes the money.
    @Test
    void anUnpaidBillCarriesItsLatestNote() throws Exception {
        UUID sessionId = openAndCloseSessionWithTime();

        addNote(sessionId, "Marco + 2").andExpect(status().isOk());

        JsonNode bills = unsettled();
        assertThat(bills).hasSize(1);
        JsonNode latest = bills.get(0).get("latestNote");
        assertThat(latest.get("body").asText()).isEqualTo("Marco + 2");
        assertThat(latest.get("kind").asText()).isEqualTo("STAFF");
        assertThat(latest.get("authorUsername").asText()).isEqualTo("note-tester");
        // The amount and the table are untouched — this is one more line on the card, not a
        // different card.
        assertThat(new BigDecimal(bills.get(0).get("totalAmount").asText()))
                .isEqualByComparingTo("120.00");
        assertThat(bills.get(0).get("tableNames").get(0).asText()).isEqualTo("Table 1");
    }

    // Nothing has been written yet, and the card still has to render. Null, not an omission.
    @Test
    void anUnpaidBillWithNoNoteCarriesNull() throws Exception {
        openAndCloseSessionWithTime();

        JsonNode bills = unsettled();
        assertThat(bills).hasSize(1);
        assertThat(bills.get(0).has("latestNote")).isTrue();
        assertThat(bills.get(0).get("latestNote").isNull()).isTrue();
    }

    // A correction is a later note, never an edit. The card shows the most recent; the thread
    // keeps both, in order, so the correction is visibly a correction.
    @Test
    void aLaterNoteCorrectsAnEarlierOneWithoutReplacingIt() throws Exception {
        UUID sessionId = openAndCloseSessionWithTime();

        addNote(sessionId, "Marco + 2").andExpect(status().isOk());
        addNote(sessionId, "Not Marco — Jun + 3").andExpect(status().isOk());

        JsonNode thread = sessionNotes(sessionId);
        assertThat(thread).hasSize(2);
        assertThat(thread.get(0).get("body").asText()).isEqualTo("Marco + 2");
        assertThat(thread.get(1).get("body").asText()).isEqualTo("Not Marco — Jun + 3");

        assertThat(unsettled().get(0).get("latestNote").get("body").asText())
                .isEqualTo("Not Marco — Jun + 3");
    }

    // Append-only, enforced by session_note_append_only in the database and not merely by the
    // absence of a route. A note about money owed that the person who owes a favour can quietly
    // remove is worse than no note at all.
    @Test
    void aNoteCannotBeUpdatedOrDeleted() throws Exception {
        UUID sessionId = openAndCloseSessionWithTime();
        UUID noteId = UUID.fromString(
                body(addNote(sessionId, "Marco + 2").andExpect(status().isOk()))
                        .get("data").get("id").asText());

        assertThatThrownBy(() -> mutate("update session_note set body = 'nobody' where id = ?", noteId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> mutate("delete from session_note where id = ?", noteId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");

        // Still there, still saying what it said.
        actAs(principal());
        assertThat(sessionNoteRepository.findBySessionId(sessionId))
                .singleElement()
                .extracting(SessionNote::getBody)
                .isEqualTo("Marco + 2");
        SecurityContextHolder.clearContext();
    }

    // The author is resolved from the authenticated session. A body that names someone else —
    // or claims to be a SYSTEM note — changes nothing, because those fields are not read.
    @Test
    void theAuthorAndTheKindCannotBeSpoofedByTheClient() throws Exception {
        UUID sessionId = openAndCloseSessionWithTime();

        JsonNode written = body(mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/notes")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Marco + 2\","
                                + "\"authorId\":\"" + otherUserId + "\","
                                + "\"authorUsername\":\"someone-else\","
                                + "\"kind\":\"SYSTEM\"}"))
                .andExpect(status().isOk())).get("data");

        assertThat(written.get("authorId").asText()).isEqualTo(userId.toString());
        assertThat(written.get("authorUsername").asText()).isEqualTo("note-tester");
        // A staff member typing a settlement cannot make one: only the server writes SYSTEM.
        assertThat(written.get("kind").asText()).isEqualTo("STAFF");
    }

    // The invariant a new endpoint has broken here before. Another branch's session is not
    // readable, not writable, and not even distinguishable from one that does not exist.
    @Test
    void aCrossBranchReadReturnsNotFound() throws Exception {
        UUID otherSessionId = openSessionAs(otherPrincipal(), otherTableId, otherCustomerTypeId);
        addNoteAs(otherPrincipal(), otherSessionId, "Their customer").andExpect(status().isOk());

        // Reading another branch's thread.
        mockMvc.perform(get("/api/v1/sessions/" + otherSessionId + "/notes").with(user(principal())))
                .andExpect(status().isNotFound());

        // Writing into it.
        addNote(otherSessionId, "Not mine to annotate").andExpect(status().isNotFound());

        // And through the bill, which is the other way in.
        UUID otherBillId = UUID.fromString(
                body(mockMvc.perform(get("/api/v1/sessions/" + otherSessionId).with(user(otherPrincipal())))
                        .andExpect(status().isOk())).get("data").get("billId").asText());
        mockMvc.perform(get("/api/v1/bills/" + otherBillId + "/notes").with(user(principal())))
                .andExpect(status().isNotFound());

        // The note itself is still there for the branch it belongs to.
        assertThat(notesAs(otherPrincipal(), otherSessionId)).hasSize(1);
    }

    // Empty and whitespace-only are 400, not an empty note in the thread. A blank line on the
    // card would look like the feature had failed.
    @Test
    void anEmptyNoteIsRejected() throws Exception {
        UUID sessionId = openAndCloseSessionWithTime();

        addNote(sessionId, "").andExpect(status().isBadRequest());
        addNote(sessionId, "   ").andExpect(status().isBadRequest());
        addNote(sessionId, "\n\t ").andExpect(status().isBadRequest());
        addNote(sessionId, "x".repeat(281)).andExpect(status().isBadRequest());

        assertThat(sessionNotes(sessionId)).isEmpty();

        // The boundary is usable, not merely declared.
        addNote(sessionId, "x".repeat(280)).andExpect(status().isOk());
    }

    // The case the counter actually hits: nobody wrote a name during the rush, and the note
    // goes on when they see the unpaid card an hour later.
    @Test
    void aNoteCanBeAddedAfterTheSessionCloses() throws Exception {
        UUID sessionId = openAndCloseSessionWithTime();

        JsonNode session = body(mockMvc.perform(get("/api/v1/sessions/" + sessionId).with(user(principal())))
                .andExpect(status().isOk())).get("data");
        assertThat(session.get("status").asText()).isEqualTo("CLOSED");

        addNote(sessionId, "Marco + 2 — said he would pay Friday").andExpect(status().isOk());
        assertThat(sessionNotes(sessionId)).hasSize(1);
    }

    // Settlement writes its own note, and the thread survives the bill leaving the strip. The
    // note is what answers "who kept playing on credit" a year from now.
    @Test
    void settlementAppendsASystemNoteAndTheThreadSurvivesIt() throws Exception {
        UUID sessionId = openAndCloseSessionWithTime();
        UUID billId = UUID.fromString(
                body(mockMvc.perform(get("/api/v1/sessions/" + sessionId).with(user(principal())))
                        .andExpect(status().isOk())).get("data").get("billId").asText());

        addNote(sessionId, "Marco + 2").andExpect(status().isOk());
        pay(billId, new BigDecimal("120.00")).andExpect(status().isOk());

        // Off the strip by the existing logic, untouched by any of this.
        assertThat(unsettled()).isEmpty();

        JsonNode thread = billNotes(billId);
        assertThat(thread).hasSize(2);
        assertThat(thread.get(0).get("body").asText()).isEqualTo("Marco + 2");
        assertThat(thread.get(0).get("kind").asText()).isEqualTo("STAFF");

        JsonNode settled = thread.get(1);
        assertThat(settled.get("kind").asText()).isEqualTo("SYSTEM");
        assertThat(settled.get("body").asText()).contains("Settled by note-tester", "CASH", "120.00");
        // Who settled it, recorded server-side — the same actor payment.taken_by carries.
        assertThat(settled.get("authorId").asText()).isEqualTo(userId.toString());
        assertThat(settled.get("createdAt").isNull()).isFalse();
    }


    // ---- helpers -----------------------------------------------------------------------

    // The trigger aborts the whole Postgres transaction along with the statement, so each
    // attempt runs inside a savepoint — otherwise the test's own transaction would be dead and
    // the assertion that the note is still there could not be made.
    private void mutate(String sql, UUID noteId) {
        jdbcTemplate.execute("SAVEPOINT append_only_probe");
        try {
            jdbcTemplate.update(sql, noteId);
        } finally {
            jdbcTemplate.execute("ROLLBACK TO SAVEPOINT append_only_probe");
        }
    }

    private UUID openAndCloseSessionWithTime() throws Exception {
        UUID sessionId = openSessionAs(principal(), tableId, customerTypeId);
        // Thirty minutes at 4.00 a minute, so there is a real 120.00 to collect.
        runForMinutes(sessionId, 30);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close").with(user(principal())))
                .andExpect(status().isOk());
        return sessionId;
    }

    private UUID openSessionAs(AppUserDetails as, UUID poolTableId, UUID customerType) throws Exception {
        return UUID.fromString(body(mockMvc.perform(post("/api/v1/sessions")
                        .with(user(as))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + poolTableId
                                + "\",\"customerTypeId\":\"" + customerType + "\"}"))
                .andExpect(status().isOk())).get("data").get("id").asText());
    }

    private ResultActions addNote(UUID sessionId, String noteBody) throws Exception {
        return addNoteAs(principal(), sessionId, noteBody);
    }

    private ResultActions addNoteAs(AppUserDetails as, UUID sessionId, String noteBody) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/notes")
                .with(user(as))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("body", noteBody))));
    }

    private JsonNode sessionNotes(UUID sessionId) throws Exception {
        return notesAs(principal(), sessionId);
    }

    private JsonNode notesAs(AppUserDetails as, UUID sessionId) throws Exception {
        return body(mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/notes").with(user(as)))
                .andExpect(status().isOk())).get("data");
    }

    private JsonNode billNotes(UUID billId) throws Exception {
        return body(mockMvc.perform(get("/api/v1/bills/" + billId + "/notes").with(user(principal())))
                .andExpect(status().isOk())).get("data");
    }

    private JsonNode unsettled() throws Exception {
        return body(mockMvc.perform(get("/api/v1/bills/unsettled").with(user(principal())))
                .andExpect(status().isOk())).get("data");
    }

    private ResultActions pay(UUID billId, BigDecimal amount) throws Exception {
        return mockMvc.perform(post("/api/v1/bills/" + billId + "/payment")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"CASH\",\"amount\":" + amount.toPlainString()
                        + ",\"tendered\":" + amount.toPlainString()
                        + ",\"idempotencyKey\":\"" + UUID.randomUUID()
                        + "\",\"billVersion\":0}"));
    }

    // Moves the segment's start back rather than waiting: the branch-scoped repositories read
    // the branch from the security context, so this runs as the principal.
    private void runForMinutes(UUID sessionId, int minutes) {
        actAs(principal());
        SessionSegment segment = sessionSegmentRepository.findBySessionId(sessionId).getFirst();
        segment.setStartedAt(OffsetDateTime.now().minusMinutes(minutes));
        sessionSegmentRepository.saveAndFlush(segment);
        SecurityContextHolder.clearContext();
    }

    private void actAs(AppUserDetails as) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(as, "unused", List.of()));
        SecurityContextHolder.setContext(context);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private AppUserDetails principal() {
        return new AppUserDetails(userId, branchId, "note-tester",
                "unused", "Note Tester", UserRole.EMPLOYEE, true);
    }

    private AppUserDetails otherPrincipal() {
        return new AppUserDetails(otherUserId, otherBranchId, "other-note-tester",
                "unused", "Other Note Tester", UserRole.EMPLOYEE, true);
    }

    private UUID branch(String code) {
        Branch branch = new Branch();
        branch.setCode(code + "-" + UUID.randomUUID().toString().substring(0, 8));
        branch.setName("Session Note Test Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        return branchRepository.saveAndFlush(branch).getId();
    }

    private UUID employee(UUID inBranch, String username) {
        AppUser user = new AppUser();
        user.setBranchId(inBranch);
        user.setUsername(username);
        user.setPasswordHash("unused");
        user.setFullName("Note Tester");
        user.setRole(UserRole.EMPLOYEE);
        user.setIsActive(true);
        return appUserRepository.saveAndFlush(user).getId();
    }

    private UUID table(UUID inBranch, String name, int number) {
        PoolTable poolTable = new PoolTable();
        poolTable.setBranchId(inBranch);
        poolTable.setName(name);
        poolTable.setTableNumber(number);
        poolTable.setIsActive(true);
        UUID poolTableId = poolTableRepository.saveAndFlush(poolTable).getId();

        PoolTableRate rate = new PoolTableRate();
        rate.setBranchId(inBranch);
        rate.setPoolTableId(poolTableId);
        rate.setRatePerMinute(new BigDecimal("4.0000"));
        rate.setEffectiveFrom(OffsetDateTime.now().minusDays(1));
        poolTableRateRepository.saveAndFlush(rate);

        return poolTableId;
    }

    private UUID customerType(UUID inBranch) {
        CustomerType customerType = new CustomerType();
        customerType.setBranchId(inBranch);
        customerType.setName("Regular");
        customerType.setAllowsRateOverride(false);
        customerType.setIsDefault(true);
        customerType.setSortOrder(1);
        return customerTypeRepository.saveAndFlush(customerType).getId();
    }
}
