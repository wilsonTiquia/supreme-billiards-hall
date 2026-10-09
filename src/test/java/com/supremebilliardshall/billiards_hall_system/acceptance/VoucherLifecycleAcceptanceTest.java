package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// No test transaction: each HTTP write commits, and read-back cannot use its persistence context.
@SpringBootTest
@AutoConfigureMockMvc
class VoucherLifecycleAcceptanceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    UUID branchId;
    UUID ownerId;
    UUID batchId;

    @BeforeEach
    void setup() throws Exception {
        branchId = jdbc.queryForObject("INSERT INTO branch(code,name,is_active) VALUES (?, 'Voucher lifecycle QA', false) RETURNING id", UUID.class, "vl-" + UUID.randomUUID());
        ownerId = jdbc.queryForObject("INSERT INTO app_user(branch_id,username,password_hash,full_name,role) VALUES (?,?,'unused','Owner','ADMIN') RETURNING id", UUID.class, branchId, "vl-" + UUID.randomUUID());
        batchId = UUID.fromString(data(mvc.perform(post("/api/v1/voucher-batches").with(user(owner()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"hours\":2,\"quantity\":3,\"expiresOn\":\"" + LocalDate.now().plusMonths(1) + "\",\"note\":\"Lifecycle draw\"}"))
                .andExpect(status().isOk())).get("id").asText());
    }

    @AfterEach
    void disableFixtureAdministrator() {
        // These requests commit. Do not leave an active admin affecting the global last-admin guard.
        if (ownerId != null) jdbc.update("UPDATE app_user SET is_active=false WHERE id=?", ownerId);
    }

    @Test
    void archiveCancelAndRestorePersistIndependentlyWithActorAndSnapshots() throws Exception {
        action("archive").andExpect(status().isOk());
        assertThat(list(false)).isEmpty();
        JsonNode archived = list(true).get(0);
        assertThat(archived.get("archivedAt").isNull()).isFalse();
        assertThat(archived.get("cancelledAt").isNull()).isTrue();
        assertThat(archived.get("outstanding").asInt()).isEqualTo(3);
        cancel(owner()).andExpect(status().isOk());
        JsonNode cancelled = list(true).get(0);
        assertThat(cancelled.get("cancelledAt").isNull()).isFalse();
        assertThat(cancelled.get("cancelled").asInt()).isEqualTo(3);
        assertThat(cancelled.get("outstanding").asInt()).isZero();
        action("restore").andExpect(status().isOk());
        JsonNode restored = list(false).get(0);
        assertThat(restored.get("archivedAt").isNull()).isTrue();
        assertThat(restored.get("cancelledAt")).isEqualTo(cancelled.get("cancelledAt"));
        assertThat(restored.get("cancelled").asInt()).isEqualTo(3);
        cancel(owner()).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id=? AND action='VOUCHER_BATCH_CANCELLED'", Long.class, batchId)).isEqualTo(1);
        var audit = jdbc.queryForMap("SELECT actor_id, before->>'cancelledAt' AS old, after->>'cancelledAt' AS current, after->>'name' AS name FROM audit_log WHERE entity_id=? AND action='VOUCHER_BATCH_CANCELLED'", batchId);
        assertThat(audit.get("actor_id")).isEqualTo(ownerId);
        assertThat(audit.get("old")).isNull();
        assertThat(audit.get("current")).isNotNull();
        assertThat(audit.get("name")).isEqualTo("Lifecycle draw");
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE entity_id=?", String.class, batchId))
                .contains("VOUCHER_BATCH_ARCHIVED", "VOUCHER_BATCH_CANCELLED", "VOUCHER_BATCH_RESTORED");
    }

    @Test
    void cancellationFiltersAndCountsKeepExpiredCodesExpired() throws Exception {
        jdbc.update("UPDATE voucher SET expires_on=DATE '2020-01-01' WHERE id=(SELECT id FROM voucher WHERE batch_id=? LIMIT 1)", batchId);
        cancel(owner()).andExpect(status().isOk());
        JsonNode batch = list(false).get(0);
        assertThat(batch.get("expired").asInt()).isEqualTo(1);
        assertThat(batch.get("cancelled").asInt()).isEqualTo(2);
        assertThat(batch.get("outstanding").asInt()).isZero();
        for (String state : new String[]{"EXPIRED", "CANCELLED", "OUTSTANDING"}) {
            JsonNode codes = data(mvc.perform(get("/api/v1/vouchers").param("batchId", batchId.toString()).param("status", state).with(user(owner()))).andExpect(status().isOk()));
            assertThat(codes).hasSize(state.equals("EXPIRED") ? 1 : state.equals("CANCELLED") ? 2 : 0);
            codes.forEach(code -> assertThat(code.get("status").asText()).isEqualTo(state));
        }
        action("archive").andExpect(status().isOk());
        action("restore").andExpect(status().isOk());
        assertThat(list(false).get(0).get("expired").asInt()).isEqualTo(1);
    }

    @Test
    void cancellationAndArchivedReadAreAdminOnlyAndBranchScoped() throws Exception {
        AppUserDetails employee = new AppUserDetails(ownerId, branchId, "employee", "unused", "Employee", UserRole.EMPLOYEE, true);
        AppUserDetails outsider = new AppUserDetails(ownerId, UUID.randomUUID(), "outsider", "unused", "Outsider", UserRole.ADMIN, true);
        cancel(employee).andExpect(status().isForbidden());
        cancel(outsider).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/voucher-batches/" + UUID.randomUUID() + "/cancel").with(user(owner()))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/voucher-batches/not-an-id/cancel").with(user(owner()))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/voucher-batches").param("includeArchived", "true").with(user(employee))).andExpect(status().isForbidden());
        assertThat(data(mvc.perform(get("/api/v1/voucher-batches").param("includeArchived", "true").with(user(outsider))).andExpect(status().isOk()))).isEmpty();
        assertThat(list(false).get(0).get("cancelledAt").isNull()).isTrue();
    }

    private AppUserDetails owner() { return new AppUserDetails(ownerId, branchId, "owner", "unused", "Owner", UserRole.ADMIN, true); }
    private ResultActions cancel(AppUserDetails actor) throws Exception { return mvc.perform(post("/api/v1/voucher-batches/" + batchId + "/cancel").with(user(actor))); }
    private ResultActions action(String action) throws Exception { return mvc.perform(post("/api/v1/setup/vouchers/" + batchId + "/" + action).with(user(owner()))); }
    private JsonNode list(boolean archived) throws Exception { return data(mvc.perform(get("/api/v1/voucher-batches").param("includeArchived", String.valueOf(archived)).with(user(owner()))).andExpect(status().isOk())); }
    private JsonNode data(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data"); }
}
