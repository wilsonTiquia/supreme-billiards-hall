package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import com.supremebilliardshall.billiards_hall_system.service.SetupKind;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SetupLifecycleTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    UUID branchId;
    UUID ownerId;

    @BeforeEach
    void setup() {
        branchId = jdbc.queryForObject("INSERT INTO branch(code,name,is_active) VALUES (?, 'Setup QA', false) RETURNING id", UUID.class, "setup-" + UUID.randomUUID());
        ownerId = staff("Owner", "ADMIN", branchId);
    }

    @ParameterizedTest
    @EnumSource(SetupKind.class)
    void unusedItemsAreReallyDeletedWithOwnedChildrenAndNamedAudit(SetupKind kind) throws Exception {
        UUID id = create(kind, "Unused");
        assertThat(item(kind, id).get("canDelete").asBoolean()).isTrue();
        remove(kind, id).andExpect(status().isOk());
        detach();
        assertThat(count(kind.getTable(), id)).isZero();
        if (kind == SetupKind.TABLES) assertThat(jdbc.queryForObject("SELECT count(*) FROM pool_table_rate WHERE pool_table_id=?", Long.class, id)).isZero();
        if (kind == SetupKind.VOUCHERS) assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher WHERE batch_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT before->>'name' FROM audit_log WHERE entity_id=? AND action=?", String.class, id, kind.getAction() + "_DELETED")).isEqualTo("Unused");
        JsonNode feed = data(mvc.perform(get("/api/v1/audit/feed").param("action", kind.getAction() + "_DELETED").with(user(owner()))).andExpect(status().isOk()));
        assertThat(feed.get("content").get(0).get("subject").asText()).isEqualTo("Unused");
    }

    @ParameterizedTest
    @EnumSource(SetupKind.class)
    void referencedItemsRefuseDeleteButArchiveAndRestoreKeepTheirHistory(SetupKind kind) throws Exception {
        UUID id = create(kind, "Used");
        reference(kind, id);
        detach();
        assertThat(item(kind, id).get("canDelete").asBoolean()).isFalse();
        String reason = item(kind, id).get("deletionReason").asText();
        remove(kind, id).andExpect(status().isConflict()).andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains(reason));
        assertThat(count(kind.getTable(), id)).isEqualTo(1);
        action(kind, id, "archive").andExpect(status().isOk());
        detach();
        assertThat(item(kind, id).get("archivedAt").isNull()).isFalse();
        action(kind, id, "restore").andExpect(status().isOk());
        detach();
        assertThat(item(kind, id).get("archivedAt").isNull()).isTrue();
        assertThat(item(kind, id).get("canDelete").asBoolean()).isFalse();
        assertThat(count(kind.getTable(), id)).isEqualTo(1);
        if (kind == SetupKind.STAFF) assertThat(jdbc.queryForObject("SELECT is_active FROM app_user WHERE id=?", Boolean.class, id)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = SetupKind.class, names = {"CATEGORIES", "TABLES", "CUSTOMER_TYPES", "EXPENSE_CATEGORIES", "STAFF"})
    void restoreExplainsAReusedNameAndLeavesOriginalArchived(SetupKind kind) throws Exception {
        UUID id = create(kind, "Shared");
        action(kind, id, "archive").andExpect(status().isOk());
        detach();
        UUID replacement = create(kind, "Shared");
        action(kind, id, "restore").andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("now in use"));
        detach();
        assertThat(item(kind, id).get("archivedAt").isNull()).isFalse();
        assertThat(item(kind, replacement).get("archivedAt").isNull()).isTrue();
    }

    @Test
    void movingTheLastProductOutDoesNotMakeItsFormerCategoryUnused() throws Exception {
        UUID id = create(SetupKind.CATEGORIES, "Former category");
        reference(SetupKind.CATEGORIES, id);
        jdbc.update("UPDATE product SET category_id=NULL WHERE branch_id=? AND category_id=?", branchId, id);
        detach();
        assertThat(item(SetupKind.CATEGORIES, id).get("canDelete").asBoolean()).isFalse();
        remove(SetupKind.CATEGORIES, id).andExpect(status().isConflict());
        assertThat(count("product_category", id)).isEqualTo(1);
    }

    @Test
    void restoreDoesNotReplaceTheNewDefaultCustomerType() throws Exception {
        UUID old = UUID.fromString(postData("/customer-types", "{\"name\":\"Old default\",\"isDefault\":true}").get("id").asText());
        action(SetupKind.CUSTOMER_TYPES, old, "archive").andExpect(status().isOk());
        detach();
        UUID current = UUID.fromString(postData("/customer-types", "{\"name\":\"New default\",\"isDefault\":true}").get("id").asText());
        action(SetupKind.CUSTOMER_TYPES, old, "restore").andExpect(status().isOk());
        detach();
        assertThat(jdbc.queryForList("SELECT id FROM customer_type WHERE branch_id=? AND is_default AND archived_at IS NULL", UUID.class, branchId)).containsExactly(current);
    }

    @Test
    void openTableCannotBeArchivedAndItsSessionSurvives() throws Exception {
        UUID table = create(SetupKind.TABLES, "Busy");
        UUID type = create(SetupKind.CUSTOMER_TYPES, "Regular");
        JsonNode session = postData("/sessions", "{\"tableId\":\"" + table + "\",\"customerTypeId\":\"" + type + "\"}");
        action(SetupKind.TABLES, table, "archive").andExpect(status().isConflict());
        detach();
        assertThat(item(SetupKind.TABLES, table).get("archivedAt").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status::text FROM table_session WHERE id=?", String.class, UUID.fromString(session.get("id").asText()))).isEqualTo("OPEN");
    }

    @Test
    void selfAndLastAdminCannotBeRemoved() throws Exception {
        remove(SetupKind.STAFF, ownerId).andExpect(status().isConflict());
        action(SetupKind.STAFF, ownerId, "archive").andExpect(status().isConflict());
        UUID onlyAdmin = staff("Only admin", "ADMIN", branchId);
        jdbc.update("UPDATE app_user SET is_active=false WHERE role='ADMIN' AND id<>?", onlyAdmin);
        detach();
        remove(SetupKind.STAFF, onlyAdmin).andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("last one"));
        assertThat(count("app_user", onlyAdmin)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(SetupKind.class)
    void otherBranchesAndEmployeesCannotUseLifecycleRoutes(SetupKind kind) throws Exception {
        UUID id = create(kind, "Private");
        UUID otherBranch = jdbc.queryForObject("INSERT INTO branch(code,name,is_active) VALUES (?, 'Other',false) RETURNING id", UUID.class, "other-" + UUID.randomUUID());
        UUID otherUser = staff("Other", "ADMIN", otherBranch);
        var outsider = new AppUserDetails(otherUser, otherBranch, "other", "", "Other", UserRole.ADMIN, true);
        JsonNode list = data(mvc.perform(get(path(kind)).with(user(outsider))).andExpect(status().isOk()));
        assertThat(list.toString()).doesNotContain(id.toString());
        mvc.perform(delete(path(kind) + "/" + id).with(user(outsider))).andExpect(status().isNotFound());
        for (String action : new String[]{"archive", "restore"}) mvc.perform(post(path(kind) + "/" + id + "/" + action).with(user(outsider))).andExpect(status().isNotFound());
        var employee = new AppUserDetails(ownerId, branchId, "employee", "", "Employee", UserRole.EMPLOYEE, true);
        mvc.perform(get(path(kind)).with(user(employee))).andExpect(status().isForbidden());
        mvc.perform(delete(path(kind) + "/" + id).with(user(employee))).andExpect(status().isForbidden());
        for (String action : new String[]{"archive", "restore"}) mvc.perform(post(path(kind) + "/" + id + "/" + action).with(user(employee))).andExpect(status().isForbidden());
        assertThat(count(kind.getTable(), id)).isEqualTo(1);
    }

    private UUID create(SetupKind kind, String name) throws Exception {
        if (kind == SetupKind.STAFF) return staff(name, "EMPLOYEE", branchId);
        String body = switch (kind) {
            case TABLES -> "{\"name\":\"" + name + "\",\"ratePerHour\":240,\"isActive\":true}";
            case VOUCHERS -> "{\"note\":\"" + name + "\",\"hours\":2,\"quantity\":2,\"expiresOn\":\"" + LocalDate.now().plusMonths(1) + "\"}";
            default -> "{\"name\":\"" + name + "\"}";
        };
        return UUID.fromString(postData(kind == SetupKind.VOUCHERS ? "/voucher-batches" : "/" + kind.getPath(), body).get("id").asText());
    }

    private void reference(SetupKind kind, UUID id) throws Exception {
        switch (kind) {
            case CATEGORIES -> postData("/products", "{\"name\":\"Beer\",\"sellingPrice\":90,\"categoryId\":\"" + id + "\"}");
            case TABLES, CUSTOMER_TYPES -> {
                UUID table = kind == SetupKind.TABLES ? id : create(SetupKind.TABLES, "Played");
                UUID type = kind == SetupKind.CUSTOMER_TYPES ? id : create(SetupKind.CUSTOMER_TYPES, "Regular");
                JsonNode session = postData("/sessions", "{\"tableId\":\"" + table + "\",\"customerTypeId\":\"" + type + "\"}");
                mvc.perform(post("/api/v1/sessions/" + session.get("id").asText() + "/close").with(user(owner()))).andExpect(status().isOk());
            }
            case EXPENSE_CATEGORIES -> postData("/expenses", "{\"expenseCategoryId\":\"" + id + "\",\"amount\":100,\"paidFromDrawer\":true}");
            case STAFF -> jdbc.update("INSERT INTO audit_log(branch_id,actor_id,action,entity_table,entity_id) VALUES (?,?,'SETUP_TEST','app_user',?)", branchId, id, id);
            case VOUCHERS -> jdbc.update("INSERT INTO audit_log(branch_id,actor_id,action,entity_table,entity_id,after) SELECT branch_id,?, 'VOUCHER_REDEEMED','bill',NULL,jsonb_build_object('code',code) FROM voucher WHERE batch_id=? LIMIT 1", ownerId, id);
        }
    }

    private UUID staff(String name, String role, UUID branch) {
        return jdbc.queryForObject("INSERT INTO app_user(branch_id,username,full_name,password_hash,role) VALUES (?,?,?,'unused',?::user_role) RETURNING id", UUID.class, branch, name + "-" + branch, name, role);
    }
    private String path(SetupKind kind) { return "/api/v1/setup/" + kind.getPath(); }
    private AppUserDetails owner() { return new AppUserDetails(ownerId, branchId, "owner", "", "Owner", UserRole.ADMIN, true); }
    private JsonNode postData(String path, String body) throws Exception { JsonNode result = data(mvc.perform(post("/api/v1" + path).with(user(owner())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())); detach(); return result; }
    private ResultActions remove(SetupKind kind, UUID id) throws Exception { return mvc.perform(delete(path(kind) + "/" + id).with(user(owner()))); }
    private ResultActions action(SetupKind kind, UUID id, String action) throws Exception { return mvc.perform(post(path(kind) + "/" + id + "/" + action).with(user(owner()))); }
    private JsonNode data(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data"); }
    private JsonNode item(SetupKind kind, UUID id) throws Exception {
        for (JsonNode row : data(mvc.perform(get(path(kind)).with(user(owner()))).andExpect(status().isOk()))) if (row.get("id").asText().equals(id.toString())) return row;
        throw new AssertionError("Missing setup row " + id);
    }
    private long count(String table, UUID id) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id=?", Long.class, id); }
    private void detach() { em.flush(); em.clear(); }
}
