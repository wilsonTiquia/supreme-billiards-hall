package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.entity.Branch;
import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.security.AdminPasswordBootstrap;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// H1 — the seeded credentials were live and there was no way to change a password. These
// prove: a V9-disabled account cannot log in, the bootstrap restores the owner exactly once
// without ever overwriting a real password, and both password-change endpoints work and are
// guarded as intended.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CredentialsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // The value V9 writes into the seeded rows. Not a valid bcrypt hash.
    private static final String SENTINEL = "DISABLED-NO-LOGIN";

    @Test
    void anAccountCarryingTheDisabledSentinelCannotLogIn() throws Exception {
        UUID branchId = givenBranch();
        String username = "disabled-" + UUID.randomUUID();
        givenUser(branchId, username, SENTINEL, UserRole.EMPLOYEE);

        // The sentinel is not bcrypt, so no password matches it. Every attempt is a 401.
        String body = """
                {"username":"%s","password":"anything-at-all"}""".formatted(username);
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theBootstrapSetsTheOwnerPasswordOnceAndNeverOverwritesARealOne() {
        // Force the seeded owner to the disabled state this migration leaves it in, so the
        // test does not depend on whether this database has already been bootstrapped. The
        // surrounding @Transactional rolls this back.
        AppUser owner = appUserRepository.findByUsernameIgnoreCaseAndArchivedAtIsNull("owner")
                .orElseThrow(() -> new IllegalStateException("seed owner missing"));
        owner.setPasswordHash(SENTINEL);
        appUserRepository.saveAndFlush(owner);

        new AdminPasswordBootstrap(appUserRepository, passwordEncoder, "first-owner-pass")
                .run(null);

        AppUser afterFirst = appUserRepository.findById(owner.getId()).orElseThrow();
        assertThat(afterFirst.getPasswordHash()).isNotEqualTo(SENTINEL);
        assertThat(passwordEncoder.matches("first-owner-pass", afterFirst.getPasswordHash())).isTrue();

        // Run again with a different value: a real hash is now present, so it must be left
        // alone — a lingering env var cannot reset an established password.
        new AdminPasswordBootstrap(appUserRepository, passwordEncoder, "some-other-pass")
                .run(null);

        AppUser afterSecond = appUserRepository.findById(owner.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("first-owner-pass", afterSecond.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("some-other-pass", afterSecond.getPasswordHash())).isFalse();
    }

    @Test
    void aUserChangesTheirOwnPasswordButOnlyWithTheCorrectCurrentOne() throws Exception {
        UUID branchId = givenBranch();
        AppUser user = givenUser(branchId, "counter-" + UUID.randomUUID(),
                passwordEncoder.encode("original-pass"), UserRole.EMPLOYEE);
        AppUserDetails principal = principalFor(user);

        // Wrong current password: refused, and the stored hash is untouched.
        mockMvc.perform(put("/api/v1/auth/password")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong-pass","newPassword":"brand-new-pass"}"""))
                .andExpect(status().isConflict());
        assertThat(passwordEncoder.matches("original-pass",
                appUserRepository.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();

        // Correct current password: the new one takes effect.
        mockMvc.perform(put("/api/v1/auth/password")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"original-pass","newPassword":"brand-new-pass"}"""))
                .andExpect(status().isOk());
        assertThat(passwordEncoder.matches("brand-new-pass",
                appUserRepository.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void anAdminResetsAnotherUsersPasswordAndAnEmployeeCannot() throws Exception {
        UUID branchId = givenBranch();
        AppUser target = givenUser(branchId, "target-" + UUID.randomUUID(), SENTINEL, UserRole.EMPLOYEE);

        // An employee is refused outright.
        mockMvc.perform(put("/api/v1/users/{id}/password", target.getId())
                        .with(user(principalFor(givenUser(branchId, "emp-" + UUID.randomUUID(),
                                SENTINEL, UserRole.EMPLOYEE))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"reset-by-employee"}"""))
                .andExpect(status().isForbidden());

        // An admin sets it, and the target can now authenticate against the new value.
        mockMvc.perform(put("/api/v1/users/{id}/password", target.getId())
                        .with(user(principalFor(givenUser(branchId, "admin-" + UUID.randomUUID(),
                                SENTINEL, UserRole.ADMIN))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"reset-by-admin"}"""))
                .andExpect(status().isOk());
        assertThat(passwordEncoder.matches("reset-by-admin",
                appUserRepository.findById(target.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    private UUID givenBranch() {
        Branch branch = new Branch();
        branch.setCode("CRED-" + UUID.randomUUID().toString().substring(0, 8));
        branch.setName("Credentials Test Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        return branchRepository.saveAndFlush(branch).getId();
    }

    private AppUser givenUser(UUID branchId, String username, String passwordHash, UserRole role) {
        AppUser user = new AppUser();
        user.setBranchId(role == UserRole.ADMIN ? null : branchId);
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setFullName("Test User");
        user.setRole(role);
        user.setIsActive(true);
        return appUserRepository.saveAndFlush(user);
    }

    private AppUserDetails principalFor(AppUser user) {
        return new AppUserDetails(user.getId(), user.getBranchId(), user.getUsername(),
                user.getPasswordHash(), user.getFullName(), user.getRole(), true);
    }
}
