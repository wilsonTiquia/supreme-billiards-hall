package com.supremebilliardshall.billiards_hall_system.acceptance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The ceiling on an API request body. Without it, /api/v1/auth/login — the one route reachable
// without a session — deserializes whatever an anonymous caller sends before it authenticates
// anybody, and a 64 MB body is accepted at the container defaults this project runs on.
@SpringBootTest
@AutoConfigureMockMvc
class RequestSizeLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Value("${supreme.request.max-body-bytes}")
    private int maxBytes;

    @Test
    void anOversizedLoginBodyIsRefusedBeforeAuthentication() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedLogin()))
                .andExpect(status().is(413))
                .andReturn().getResponse().getContentAsString();

        // Refused in the envelope the SPA understands, not through the container's error page.
        assertThat(body).contains("\"success\":false");
    }

    // The limit is imposed ahead of the security chain, so an oversized body costs no session
    // lookup and no database work — the refusal comes back before the 401 would have.
    @Test
    void anOversizedBodyIsRefusedBeforeTheSessionIsEvenLookedUp() throws Exception {
        mockMvc.perform(post("/api/v1/bills/00000000-0000-0000-0000-000000000000/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized()))
                .andExpect(status().is(413));
    }

    // The ceiling is far above anything the SPA sends, so ordinary traffic is untouched: this
    // body is rejected for the credentials, which means it got all the way to the login.
    @Test
    void anOrdinaryLoginBodyIsUnaffected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    // Nothing outside /api/v1 is bounded here — the SPA shell is served by GET and has no body.
    // Whether index.html is on the test classpath is beside the point; it must not be a 413.
    @Test
    void theStaticShellIsNotAffected() throws Exception {
        int status = mockMvc.perform(get("/"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotEqualTo(413);
    }

    private String oversizedLogin() {
        return "{\"username\":\"" + "A".repeat(maxBytes + 1024) + "\",\"password\":\"x\"}";
    }

    private String oversized() {
        return "{\"note\":\"" + "A".repeat(maxBytes + 1024) + "\"}";
    }
}
