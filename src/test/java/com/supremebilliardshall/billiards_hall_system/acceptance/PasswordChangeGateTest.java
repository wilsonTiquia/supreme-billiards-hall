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

import org.springframework.core.NestedExceptionUtils;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;

import java.net.URI;
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

    // The gate used to decide from request.getRequestURI(), which is the RAW request target,
    // while Tomcat and Spring both route on the decoded path. /%61pi/v1/tables therefore
    // reached the controller as /api/v1/tables while the gate saw no API call at all and let
    // it through. Every shape below is one way of writing an API path that a hand-rolled
    // string comparison reads differently from the router.
    @Test
    void theGateCannotBeBypassedByRewritingThePath() throws Exception {
        String username = givenFlaggedUser("start-pass");
        MockHttpSession session = loginSession(username, "start-pass");

        // The control: the plain path is refused, so the shapes below are compared against
        // a gate that is definitely working.
        assertGated("/api/v1/tables", session);

        // The bypass this test exists for — an ordinary letter, percent-encoded.
        assertGated("/%61pi/v1/tables", session);
        assertGated("/api/v%31/tables", session);

        // Encoding inside the path must not turn one endpoint into another.
        assertGated("/api/v1/table%73", session);

        // Trailing slash: still the API, still refused.
        assertGated("/api/v1/tables/", session);

        // Shapes the framework refuses before the gate has to have an opinion: double
        // slashes, dot segments, traversal and case variation all fail to match the route,
        // so no controller is reached. Which layer says no is not the point — the point is
        // that no API payload comes back.
        assertNoApiPayload("//api/v1/tables", session);
        assertNoApiPayload("/api//v1/tables", session);
        assertNoApiPayload("/api/./v1/tables", session);
        assertNoApiPayload("/api/v1/../v1/tables", session);
        assertNoApiPayload("/API/v1/tables", session);
    }

    // A gated user may not write anywhere outside the three escape routes, including at
    // paths that are not the API at all — the allowlist names its methods rather than
    // trusting "anything that is not /api/v1".
    @Test
    void aFlaggedUserCannotWriteOutsideTheApi() throws Exception {
        String username = givenFlaggedUser("start-pass");
        MockHttpSession session = loginSession(username, "start-pass");

        mockMvc.perform(post("/anything").session(session))
                .andExpect(status().isForbidden());
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

    // Refused by the gate itself, with the marker the SPA routes on.
    private void assertGated(String rawTarget, MockHttpSession session) throws Exception {
        String body = mockMvc.perform(get(URI.create(rawTarget)).session(session))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(body)
                .as("%s must be refused by the password-change gate", rawTarget)
                .contains("PASSWORD_CHANGE_REQUIRED");
    }

    /*
     * Refused by something -- the gate, the firewall, or the router. The security claim is only
     * that the API did not answer, so that is what is asserted.
     *
     * The catch is narrowed to the ONE exception that means "the firewall refused this", and
     * anything else is rethrown. It used to be `catch (Exception) { return; }`, which turned
     * every possible failure into a pass: an unrelated error anywhere in the filter chain read
     * as proof of safety, and the test would have reported a broken application as a secure
     * one. A test that cannot go red is worse than no test, and one that absorbs OTHER tests'
     * failures is worse again.
     */
    private void assertNoApiPayload(String rawTarget, MockHttpSession session) throws Exception {
        MockHttpServletResponse response;
        try {
            response = mockMvc.perform(get(URI.create(rawTarget)).session(session))
                    .andReturn().getResponse();
        } catch (Exception thrown) {
            if (NestedExceptionUtils.getMostSpecificCause(thrown) instanceof RequestRejectedException) {
                // Spring Security's HttpFirewall rejects some of these before any handler runs.
                return;
            }
            throw thrown;
        }

        /*
         * DO NOT "STRENGTHEN" THIS INTO A STATUS ASSERTION. It was tried and it is wrong.
         *
         * A 200 is not a bypass here. The SPA shell is public and answers any path the router
         * does not match, so //api/v1/tables comes back 200 with index.html -- which is the
         * behaviour SecurityConfig documents on purpose: the shell carries no data, the login
         * screen is part of it, and every figure the app displays comes from /api/v1. Asserting
         * a non-2xx status here fails on correct behaviour.
         *
         * The claim is narrower and exact: no API call was SERVED. Every controller answers
         * through APIResponse, so a success envelope is the signature of one having run, and
         * its absence is the whole assertion.
         *
         * The defect this helper had was never the assertion -- it was the catch above, which
         * used to be `catch (Exception) { return; }` and turned any failure at all into a pass.
         */
        assertThat(response.getContentAsString())
                .as("%s must not reach the API (status was %d)", rawTarget, response.getStatus())
                .doesNotContain("\"success\":true");
    }

    private MockHttpSession loginSession(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
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
