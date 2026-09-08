package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.entity.Branch;
import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * Who can sign in, and the invariant that there is always somebody who can fix it.
 *
 * The last-admin guard is the reason this feature exists: DELETE /users/lockouts is ADMIN-only,
 * so a hall with no administrator has no way to clear a lockout and only the break-glass
 * procedure in HELP.md to get back in. Every refusal below is a wall in front of that state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserAdministrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Persistence assertions read the row, never the entity that is still in the context.
    @PersistenceContext
    private EntityManager entityManager;

    private UUID branchId;
    private UUID adminId;
    private UUID employeeId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("USR" + UUID.randomUUID().toString().substring(0, 6));
        branch.setName("User Admin Test Branch");
        branch.setNextReceiptNo(1L);
        // Inactive, like every other test branch here: an active one defeats the
        // single-branch default a global admin relies on, and these rows outlive the test.
        branch.setIsActive(false);
        branchId = branchRepository.saveAndFlush(branch).getId();

        // Branch-bound rather than global, so this admin appears in its own branch's list and
        // the test can act on the same rows the Staff screen shows.
        adminId = givenUser("acting-admin-", UserRole.ADMIN, branchId);
        employeeId = givenUser("acting-employee-", UserRole.EMPLOYEE, branchId);
    }

    // ── The flow ──────────────────────────────────────────────────────────────────────

    @Test
    void anAdminAddsAnEmployeeWhoIsThenForcedToChangeTheHandedOverPassword() throws Exception {
        String username = "new-hire-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = data(create(admin(), username, "Sunday Closer", "EMPLOYEE", "handed-over-1")
                .andExpect(status().isOk()));

        assertThat(created.get("role").asText()).isEqualTo("EMPLOYEE");
        assertThat(created.get("active").asBoolean()).isTrue();
        // The whole point of a temporary: the admin says it out loud, so it must not survive.
        assertThat(created.get("mustChangePassword").asBoolean()).isTrue();

        detach();
        AppUser row = appUserRepository.findByUsernameIgnoreCaseAndArchivedAtIsNull(username).orElseThrow();
        assertThat(row.getMustChangePassword()).isTrue();
        // An EMPLOYEE must belong to a branch — app_user_branch_required_for_employee.
        assertThat(row.getBranchId()).isEqualTo(branchId);

        // They sign in, and can do nothing until the password is theirs.
        MockHttpSession session = loginSession(username, "handed-over-1");
        mockMvc.perform(get("/api/v1/tables").session(session))
                .andExpect(status().isForbidden())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("PASSWORD_CHANGE_REQUIRED"));

        mockMvc.perform(put("/api/v1/auth/password").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"handed-over-1","newPassword":"their-own-pass"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/tables").session(session)).andExpect(status().isOk());
    }

    /*
     * The recovery route the whole exercise exists to create.
     *
     * Before this feature every user came from the V2 seed, so there was exactly one admin;
     * five bad passwords on that account shut the only door to DELETE /users/lockouts. A second
     * admin is what makes the documented unlock real, so it is asserted end to end rather than
     * inferred from the row.
     */
    @Test
    void aSecondAdminCanClearLockoutsWhichIsWhyThisFeatureExists() throws Exception {
        String username = "second-admin-" + UUID.randomUUID().toString().substring(0, 8);
        create(admin(), username, "Second Owner", "ADMIN", "temp-admin-1").andExpect(status().isOk());

        detach();
        AppUser row = appUserRepository.findByUsernameIgnoreCaseAndArchivedAtIsNull(username).orElseThrow();
        assertThat(row.getRole()).isEqualTo(UserRole.ADMIN);
        // An ADMIN may be global, and following the seeded owner that is what a new one is.
        assertThat(row.getBranchId()).isNull();

        MockHttpSession session = loginSession(username, "temp-admin-1");
        // The gate stands in front of everything, including the unlock route, so the new admin
        // takes ownership of the password first. That is the flow, not an obstacle to it.
        mockMvc.perform(delete("/api/v1/users/lockouts").session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/auth/password").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"temp-admin-1","newPassword":"second-owner-pass"}"""))
                .andExpect(status().isOk());

        // And now the door opens: this admin can free a colleague locked out at the till.
        mockMvc.perform(delete("/api/v1/users/lockouts").session(session))
                .andExpect(status().isOk());
    }

    // ── The guards ────────────────────────────────────────────────────────────────────

    @Test
    void youCannotArchiveYourself() throws Exception {
        mockMvc.perform(delete("/api/v1/users/" + adminId).with(user(admin())))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("cannot archive your own account"));

        detach();
        assertThat(appUserRepository.findById(adminId).orElseThrow().getArchivedAt()).isNull();
    }

    @Test
    void youCannotDemoteYourself() throws Exception {
        mockMvc.perform(update(adminId, admin(), "Acting Admin", "EMPLOYEE", true))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("cannot remove your own administrator role"));

        detach();
        assertThat(appUserRepository.findById(adminId).orElseThrow().getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void youCannotDeactivateYourself() throws Exception {
        mockMvc.perform(update(adminId, admin(), "Acting Admin", "ADMIN", false))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("cannot deactivate your own account"));

        detach();
        assertThat(appUserRepository.findById(adminId).orElseThrow().getIsActive()).isTrue();
    }

    // With two administrators, retiring one is ordinary and allowed. This is the control for
    // the test below: the refusal there has to be about the LAST admin, not about admins.
    @Test
    void withTwoAdministratorsOneCanBeRetired() throws Exception {
        UUID otherAdminId = givenUser("other-admin-", UserRole.ADMIN, branchId);
        AppUserDetails other = principal(otherAdminId, "other-admin", UserRole.ADMIN);

        mockMvc.perform(delete("/api/v1/users/" + adminId).with(user(other)))
                .andExpect(status().isOk());

        detach();
        assertThat(appUserRepository.findById(adminId).orElseThrow().getArchivedAt()).isNotNull();
    }

    /*
     * The dead end this feature exists to make unreachable: no administrator left, so nobody
     * can clear a lockout and break-glass is the only way back in.
     *
     * REACHED THROUGH A STALE ADMIN SESSION, and that is not contrivance -- it is the only way
     * this guard can fire, and worth knowing. Every route here needs ADMIN authority, so if the
     * target is the last administrator then the caller normally IS the target, and the
     * cannot-touch-yourself rules answer first. What survives them is a principal still
     * carrying ADMIN authority whose row no longer does: a session opened before a demotion,
     * on a phone or a second browser. UserController invalidates sessions on exactly this
     * change, but that is a mitigation, not a guarantee, and this is the wall behind it.
     *
     * The principal below is the employee fixture holding ADMIN authority, which is precisely
     * that shape.
     */
    @Test
    void theLastAdministratorCannotBeArchivedDemotedOrDeactivated() throws Exception {
        AppUserDetails staleAdminSession = principal(employeeId, "acting-employee", UserRole.ADMIN);

        /*
         * The count is SYSTEM-WIDE, not per branch, because an ADMIN may be global and the
         * recovery it protects -- DELETE /users/lockouts -- is global too. So the seeded owner
         * from V2 counts, and has to be out of the way before this branch's admin is the last
         * one anywhere. Rolled back with the test.
         *
         * Worth knowing beyond this test: in the real deployment that seeded owner is exactly
         * this backstop, which is why the guard almost never fires there.
         */
        appUserRepository.findByUsernameIgnoreCaseAndArchivedAtIsNull("owner")
                .ifPresent(seeded -> {
                    seeded.setIsActive(false);
                    appUserRepository.saveAndFlush(seeded);
                });
        detach();

        mockMvc.perform(delete("/api/v1/users/" + adminId).with(user(staleAdminSession)))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("no administrator left"));
        mockMvc.perform(update(adminId, staleAdminSession, "Acting Admin", "EMPLOYEE", true))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("no administrator left"));
        mockMvc.perform(update(adminId, staleAdminSession, "Acting Admin", "ADMIN", false))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("no administrator left"));

        detach();
        AppUser survivor = appUserRepository.findById(adminId).orElseThrow();
        assertThat(survivor.getArchivedAt()).isNull();
        assertThat(survivor.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(survivor.getIsActive()).isTrue();
    }

    // ── Names ─────────────────────────────────────────────────────────────────────────

    @Test
    void aUsernameInUseIsRefusedWithTheFixNamed() throws Exception {
        String username = "taken-" + UUID.randomUUID().toString().substring(0, 8);
        create(admin(), username, "First Person", "EMPLOYEE", "first-pass-1").andExpect(status().isOk());

        create(admin(), username, "Second Person", "EMPLOYEE", "second-pass-1")
                .andExpect(status().isConflict())
                // Named, not "that change conflicts with an existing record": the raw partial
                // index violation tells the owner nothing they can act on.
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("is already taken"));
    }

    // app_user_username_key is partial on archived_at IS NULL, so archiving hands the name
    // back. That is deliberate — a replacement should be able to take it.
    @Test
    void archivingAUserFreesTheUsernameForReuse() throws Exception {
        String username = "reused-" + UUID.randomUUID().toString().substring(0, 8);
        UUID firstId = UUID.fromString(
                data(create(admin(), username, "Leaver", "EMPLOYEE", "leaver-pass-1")
                        .andExpect(status().isOk())).get("id").asText());

        mockMvc.perform(delete("/api/v1/users/" + firstId).with(user(admin())))
                .andExpect(status().isOk());
        detach();

        create(admin(), username, "Replacement", "EMPLOYEE", "joiner-pass-1")
                .andExpect(status().isOk());

        detach();
        // The archived row is still there, holding its history; the live one is the new person.
        assertThat(appUserRepository.findById(firstId).orElseThrow().getArchivedAt()).isNotNull();
        assertThat(appUserRepository.findByUsernameIgnoreCaseAndArchivedAtIsNull(username)
                .orElseThrow().getFullName()).isEqualTo("Replacement");
    }

    // ── The boundary ──────────────────────────────────────────────────────────────────

    @Test
    void anEmployeeIsRefusedEveryUserAdministrationRoute() throws Exception {
        AppUserDetails staff = principal(employeeId, "acting-employee", UserRole.EMPLOYEE);

        mockMvc.perform(get("/api/v1/users").with(user(staff))).andExpect(status().isForbidden());
        create(staff, "sneaky", "Sneaky", "ADMIN", "sneaky-pass-1")
                .andExpect(status().isForbidden());
        mockMvc.perform(update(employeeId, staff, "Sneaky", "ADMIN", true))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/users/" + adminId).with(user(staff)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/users/lockouts").with(user(staff)))
                .andExpect(status().isForbidden());

        detach();
        // Nothing stuck to anything through any of that.
        assertThat(appUserRepository.findByUsernameIgnoreCaseAndArchivedAtIsNull("sneaky")).isEmpty();
        assertThat(appUserRepository.findById(employeeId).orElseThrow().getRole())
                .isEqualTo(UserRole.EMPLOYEE);
    }

    // The Staff screen has to show the accounts it manages. A global admin belongs to no
    // branch, so a branch-only list showed none of them and Edit, Archive and the last-admin
    // warning were dead for exactly those rows.
    @Test
    void theStaffListShowsThisBranchAndTheGlobalAdmins() throws Exception {
        String globalAdmin = "global-admin-" + UUID.randomUUID().toString().substring(0, 8);
        create(admin(), globalAdmin, "Global Owner", "ADMIN", "global-pass-1").andExpect(status().isOk());
        detach();

        String listed = mockMvc.perform(get("/api/v1/users").with(user(admin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(listed).contains(globalAdmin);        // global, no branch
        assertThat(listed).contains("acting-employee");  // this branch
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────

    private ResultActions create(AppUserDetails as, String username, String fullName,
                                 String role, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/users")
                .with(user(as))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","fullName":"%s","role":"%s","temporaryPassword":"%s"}"""
                        .formatted(username, fullName, role, password)));
    }

    private org.springframework.test.web.servlet.RequestBuilder update(UUID id, AppUserDetails as,
                                                                       String fullName, String role,
                                                                       boolean active) {
        return put("/api/v1/users/" + id)
                .with(user(as))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"%s","role":"%s","isActive":%s}"""
                        .formatted(fullName, role, active));
    }

    private MockHttpSession loginSession(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}""".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private UUID givenUser(String prefix, UserRole role, UUID branch) {
        AppUser appUser = new AppUser();
        appUser.setBranchId(role == UserRole.ADMIN ? branch : branch);
        appUser.setUsername(prefix + UUID.randomUUID());
        appUser.setPasswordHash(passwordEncoder.encode("unused-fixture-pass"));
        appUser.setFullName("Fixture " + role);
        appUser.setRole(role);
        appUser.setIsActive(true);
        return appUserRepository.saveAndFlush(appUser).getId();
    }

    private AppUserDetails admin() {
        return principal(adminId, "acting-admin", UserRole.ADMIN);
    }

    private AppUserDetails principal(UUID id, String username, UserRole role) {
        return new AppUserDetails(id, branchId, username, "unused", "Fixture", role, true);
    }

    private JsonNode data(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }

    /*
     * Push the pending writes and forget every managed entity, so the next read comes from the
     * row rather than the persistence context. Without this an assertion about what PERSISTED
     * cannot fail — see the rule in CLAUDE.md, and cash_count.counted_at, which is the case
     * that proves it.
     */
    private void detach() {
        entityManager.flush();
        entityManager.clear();
    }
}
