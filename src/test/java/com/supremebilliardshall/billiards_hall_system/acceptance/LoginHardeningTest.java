package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.entity.Branch;
import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// M4 and M5 — the manual login had no session-fixation protection and no throttling, on a
// now internet-reachable address. These prove the session id rotates on login and that
// repeated failures lock the account out.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginHardeningTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void theSessionIdRotatesOnASuccessfulLogin() throws Exception {
        String username = givenUserWithPassword("rotate-me-pass").getUsername();

        // A pre-existing session, as a fixation attacker would have planted.
        MockHttpSession session = new MockHttpSession();
        String originalId = session.getId();

        mockMvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "rotate-me-pass")))
                .andExpect(status().isOk());

        // Same session object, new id: the id the client arrived with no longer identifies
        // the authenticated session.
        assertThat(session.getId()).isNotEqualTo(originalId);
    }

    @Test
    void repeatedFailedLoginsAreLockedOut() throws Exception {
        String username = givenUserWithPassword("correct-pass").getUsername();
        // A source address unique to this test, so the lockout it triggers cannot bleed into
        // any other login test sharing the default address.
        RequestPostProcessor fromThisTest = fromAddress("203.0.113." + (1 + (int) (Math.random() * 250)));

        // Five wrong attempts are each a plain 401.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(fromThisTest)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(username, "wrong-pass")))
                    .andExpect(status().isUnauthorized());
        }

        // The sixth is locked out — and stays locked even with the correct password, so the
        // lock is not a wrong-password response in disguise.
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromThisTest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "correct-pass")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aLockedOutUsernameStillLogsInFromADifferentAddress() throws Exception {
        String username = givenUserWithPassword("correct-pass").getUsername();

        // Lock the account out from one address.
        RequestPostProcessor attacker = fromAddress("203.0.113." + (1 + (int) (Math.random() * 120)));
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login").with(attacker)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(username, "wrong-pass")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login").with(attacker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "correct-pass")))
                .andExpect(status().isTooManyRequests());

        // The same username from a DIFFERENT address is not locked — nobody can lock the till
        // out of its own account by hammering it from somewhere else. A wrong password here is
        // a plain 401, not a lockout.
        mockMvc.perform(post("/api/v1/auth/login").with(fromAddress("198.51.100.42"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "wrong-pass")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminCanClearLockouts() throws Exception {
        String username = givenUserWithPassword("correct-pass").getUsername();
        RequestPostProcessor sameAddress = fromAddress("203.0.113." + (130 + (int) (Math.random() * 120)));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login").with(sameAddress)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(username, "wrong-pass")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login").with(sameAddress)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "correct-pass")))
                .andExpect(status().isTooManyRequests());

        // An admin clears the lockouts...
        mockMvc.perform(delete("/api/v1/users/lockouts")
                        .with(user(adminPrincipal())))
                .andExpect(status().isOk());

        // ...and the account is reachable again from the same address.
        mockMvc.perform(post("/api/v1/auth/login").with(sameAddress)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "correct-pass")))
                .andExpect(status().isOk());
    }

    @Test
    void changingYourOwnPasswordIsThrottled() throws Exception {
        AppUser user = givenUserWithPassword("current-pass");
        AppUserDetails principal = new AppUserDetails(user.getId(), user.getBranchId(),
                user.getUsername(), user.getPasswordHash(), user.getFullName(), user.getRole(), true);

        // Five wrong current-password attempts are each a plain 409.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(put("/api/v1/auth/password").with(user(principal))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"guess-%d","newPassword":"would-be-new-pass"}""".formatted(i)))
                    .andExpect(status().isConflict());
        }

        // The sixth is locked out, so the current password cannot be brute-forced.
        mockMvc.perform(put("/api/v1/auth/password").with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"current-pass","newPassword":"would-be-new-pass"}"""))
                .andExpect(status().isTooManyRequests());
    }

    private RequestPostProcessor fromAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private AppUserDetails adminPrincipal() {
        return new AppUserDetails(UUID.randomUUID(), null, "admin",
                "unused", "Admin", UserRole.ADMIN, true);
    }

    private String loginBody(String username, String password) {
        return """
                {"username":"%s","password":"%s"}""".formatted(username, password);
    }

    private AppUser givenUserWithPassword(String rawPassword) {
        Branch branch = new Branch();
        branch.setCode("LH-" + UUID.randomUUID().toString().substring(0, 8));
        branch.setName("Login Hardening Test Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        UUID branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername("login-" + UUID.randomUUID());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName("Login Tester");
        user.setRole(UserRole.EMPLOYEE);
        user.setIsActive(true);
        return appUserRepository.saveAndFlush(user);
    }
}
