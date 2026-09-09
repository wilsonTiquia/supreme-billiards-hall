package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// A path variable or query parameter that will not convert, answered by name.
//
// Every one of these used to come back "Invalid ID format", because the handler had a single
// message and the malformed UUID was the only case anybody had hit. A malformed ?date= on a
// report therefore told the owner to check an id the request does not carry -- which is not a
// small thing on the one screen where the date IS the input.
//
// Conversion fails before the controller method runs, so nothing here needs a fixture: the
// request never reaches a service and never touches the database.
@SpringBootTest
@AutoConfigureMockMvc
class MalformedRequestTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aMalformedIdStillSaysId() throws Exception {
        assertThat(messageOf(get("/api/v1/bills/not-a-uuid")))
                .isEqualTo("Invalid ID format");
    }

    // The case that was wrong. A date is not an ID.
    @Test
    void aMalformedDateSaysDate() throws Exception {
        assertThat(messageOf(get("/api/v1/reports/daily?date=yesterday")))
                .isEqualTo("Invalid date format");
    }

    // Anything else names the parameter rather than guessing at what it was for. The point is
    // that the next parameter type someone adds cannot inherit a message about ids.
    @Test
    void anythingElseNamesTheParameter() throws Exception {
        assertThat(messageOf(get("/api/v1/audit/feed?page=first")))
                .isEqualTo("Invalid value for 'page'");
    }

    private String messageOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        ResultActions actions = mockMvc.perform(request.with(user(admin())))
                .andExpect(status().isBadRequest());
        // Read as text rather than parsed: the message is the whole subject of these tests.
        String body = actions.andReturn().getResponse().getContentAsString();
        int start = body.indexOf("\"message\":\"") + "\"message\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    // No database row behind it, and none needed: these requests die at argument conversion.
    private AppUserDetails admin() {
        return new AppUserDetails(UUID.randomUUID(), UUID.randomUUID(), "malformed-tester",
                "unused", "Malformed Tester", UserRole.ADMIN, true);
    }
}
