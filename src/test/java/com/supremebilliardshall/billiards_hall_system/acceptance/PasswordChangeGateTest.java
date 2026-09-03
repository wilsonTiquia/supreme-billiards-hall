package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.entity.Branch;
import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The forced password-change gate: a user carrying must_change_password can do nothing but
// read who they are, change their password, and log out — and is fully functional the instant
// they change it. Driven through a real session so the gate sees the session principal.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasswordChangeGateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void aFlaggedUserIsGatedToThePasswordChangeAndFreedByIt() throws Exception {
        String username = givenFlaggedUser("start-pass");

        // Log in and keep the session the app established.
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "start-pass")))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // A representative business endpoint is refused, with the distinct marker.
        String refused = mockMvc.perform(get("/api/v1/tables").session(session))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(refused).contains("PASSWORD_CHANGE_REQUIRED");

        // /auth/me is allowed and reports the flag, so the SPA knows on load.
        String me = mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(me).contains("\"mustChangePassword\":true");

        // Changing the password is allowed...
        mockMvc.perform(put("/api/v1/auth/password").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"start-pass","newPassword":"the-new-pass"}"""))
                .andExpect(status().isOk());

        // ...and the same session is fully functional immediately, no re-login.
        mockMvc.perform(get("/api/v1/tables").session(session))
                .andExpect(status().isOk());

        // The flag is cleared in the database too.
        assertThat(appUserRepository.findByUsernameIgnoreCaseAndArchivedAtIsNull(username)
                .orElseThrow().getMustChangePassword()).isFalse();
    }

    @Test
    void aFlaggedUserMayStillLogOut() throws Exception {
        String username = givenFlaggedUser("start-pass");
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "start-pass")))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/api/v1/auth/logout").session(session))
                .andExpect(status().isOk());
    }

    private String loginBody(String username, String password) {
        return """
                {"username":"%s","password":"%s"}""".formatted(username, password);
    }

    private String givenFlaggedUser(String rawPassword) {
        Branch branch = new Branch();
        branch.setCode("GATE-" + UUID.randomUUID().toString().substring(0, 8));
        branch.setName("Gate Test Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        UUID branchId = branchRepository.saveAndFlush(branch).getId();

        String username = "flagged-" + UUID.randomUUID();
        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName("Flagged User");
        user.setRole(UserRole.EMPLOYEE);
        user.setIsActive(true);
        user.setMustChangePassword(true);
        appUserRepository.saveAndFlush(user);
        return username;
    }
}
