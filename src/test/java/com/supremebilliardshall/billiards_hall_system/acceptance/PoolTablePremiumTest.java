package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PoolTablePremiumTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    UUID branchId;
    UUID ownerId;

    @BeforeEach
    void setup() {
        branchId = jdbc.queryForObject("INSERT INTO branch(code,name,is_active) VALUES (?, 'Premium QA', false) RETURNING id", UUID.class, "premium-" + UUID.randomUUID());
        ownerId = jdbc.queryForObject("INSERT INTO app_user(branch_id,username,password_hash,full_name,role) VALUES (?, ?, 'unused', 'Owner', 'ADMIN') RETURNING id", UUID.class, branchId, "premium-" + UUID.randomUUID());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"ratePerMinute\":4.1234", "\"ratePerHour\":200"})
    void classificationPersistsAuditsAndNeverRepricesAnEdit(String rate) throws Exception {
        UUID id = UUID.fromString(postData("/tables", "{\"name\":\"Named Premium but Standard\"," + rate + "}").get("id").asText());
        assertThat(floorTable(id).get("isPremium").asBoolean()).isFalse();
        String rateRows = rateRows(id);
        edit(id, rate, ",\"isPremium\":true");
        assertThat(floorTable(id).get("isPremium").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT is_premium FROM pool_table WHERE id=?", Boolean.class, id)).isTrue();
        JsonNode audit = json.readTree(jdbc.queryForObject("SELECT after::text FROM audit_log WHERE entity_id=? AND action='POOL_TABLE_UPDATED' ORDER BY occurred_at DESC, id DESC LIMIT 1", String.class, id));
        assertThat(audit.get("isPremium").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT before->>'isPremium' FROM audit_log WHERE entity_id=? AND action='POOL_TABLE_UPDATED' ORDER BY occurred_at DESC, id DESC LIMIT 1", String.class, id)).isEqualTo("false");
        edit(id, rate, ""); // An older client omits the flag.
        assertThat(floorTable(id).get("isPremium").asBoolean()).isTrue();
        edit(id, rate, ",\"isPremium\":false");
        assertThat(floorTable(id).get("isPremium").asBoolean()).isFalse();
        assertThat(rateRows(id)).isEqualTo(rateRows);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='POOL_TABLE_RATE_CHANGED' AND branch_id=?", Integer.class, branchId)).isZero();
        JsonNode premium = postData("/tables", "{\"name\":\"Explicit premium\",\"ratePerMinute\":2,\"isPremium\":true}");
        assertThat(floorTable(UUID.fromString(premium.get("id").asText())).get("isPremium").asBoolean()).isTrue();
    }

    @Test
    void activeSessionHistoricalReportsAndReceiptKeepTheirFiguresAcrossClassificationChanges() throws Exception {
        UUID table = UUID.fromString(postData("/tables", "{\"name\":\"Same name\",\"ratePerHour\":200}").get("id").asText());
        UUID customer = jdbc.queryForObject("INSERT INTO customer_type(branch_id,name,is_default) VALUES (?, 'Regular', true) RETURNING id", UUID.class, branchId);
        JsonNode session = postData("/sessions", "{\"tableId\":\"" + table + "\",\"customerTypeId\":\"" + customer + "\"}");
        UUID sessionId = UUID.fromString(session.get("id").asText());
        UUID billId = UUID.fromString(session.get("billId").asText());
        jdbc.update("UPDATE table_session SET opened_at=opened_at-interval '180 minutes' WHERE id=?", sessionId);
        jdbc.update("UPDATE session_segment SET started_at=started_at-interval '180 minutes' WHERE session_id=?", sessionId);
        String rateRows = rateRows(table);
        String segments = jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(s) ORDER BY id)::text FROM session_segment s WHERE session_id=?", String.class, sessionId);
        edit(table, "\"ratePerHour\":200", ",\"isPremium\":true");
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(s) ORDER BY id)::text FROM session_segment s WHERE session_id=?", String.class, sessionId)).isEqualTo(segments);
        JsonNode closed = postData("/sessions/" + sessionId + "/close", "{}");
        assertThat(new BigDecimal(closed.get("timeAmount").asText())).isEqualByComparingTo("599.99");
        JsonNode bill = getData("/bills/" + billId);
        JsonNode payment = postData("/bills/" + billId + "/payment", "{\"method\":\"CASH\",\"amount\":599.99,\"tendered\":600,\"billVersion\":" + bill.get("version").asLong() + ",\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}");
        String date = payment.get("businessDate").asText();
        JsonNode dailyBefore = getData("/reports/daily?date=" + date);
        JsonNode periodBefore = getData("/reports/period?from=" + date + "&to=" + date);
        JsonNode receiptBefore = getData("/bills/" + billId + "/receipt");
        assertThat(dailyBefore.get("tableUtilisation").get(0).get("tableId").asText()).isEqualTo(table.toString());
        assertThat(dailyBefore.get("tableUtilisation").get(0).get("isPremium").asBoolean()).isTrue();
        assertThat(periodBefore.get("tables").get(0).get("isPremium").asBoolean()).isTrue();
        assertThat(periodBefore.get("tables").get(0).get("tableId").asText()).isEqualTo(table.toString());
        assertThat(new BigDecimal(periodBefore.get("tables").get(0).get("timeRevenue").asText())).isEqualByComparingTo("599.99");
        edit(table, "\"ratePerHour\":200", ",\"isPremium\":false");
        JsonNode dailyAfter = getData("/reports/daily?date=" + date);
        JsonNode periodAfter = getData("/reports/period?from=" + date + "&to=" + date);
        assertThat(dailyAfter.get("totals")).isEqualTo(dailyBefore.get("totals"));
        assertThat(periodAfter.get("headline")).isEqualTo(periodBefore.get("headline"));
        assertThat(dailyAfter.get("tableUtilisation").get(0).get("isPremium").asBoolean()).isFalse();
        assertThat(periodAfter.get("tables").get(0).get("isPremium").asBoolean()).isFalse();
        for (String field : new String[]{"tableId", "occupiedMinutes", "utilisationPercent", "timeRevenue", "revenuePerOccupiedHour"}) {
            assertThat(periodAfter.get("tables").get(0).get(field)).isEqualTo(periodBefore.get("tables").get(0).get(field));
        }
        assertThat(getData("/bills/" + billId + "/receipt")).isEqualTo(receiptBefore);
        assertThat(rateRows(table)).isEqualTo(rateRows);
    }

    @Test
    void classificationWritesAndReportRowsRemainRoleAndBranchScoped() throws Exception {
        UUID table = UUID.fromString(postData("/tables", "{\"name\":\"Table 1\",\"ratePerMinute\":5}").get("id").asText());
        UUID otherBranch = jdbc.queryForObject("INSERT INTO branch(code,name,is_active) VALUES (?, 'Other premium hall', false) RETURNING id", UUID.class, "other-" + UUID.randomUUID());
        UUID otherTable = jdbc.queryForObject("INSERT INTO pool_table(branch_id,name,table_number,is_premium) VALUES (?, 'Table 1', 1, true) RETURNING id", UUID.class, otherBranch);
        String request = "{\"name\":\"Table 1\",\"ratePerMinute\":5,\"isPremium\":true}";
        AppUserDetails employee = new AppUserDetails(ownerId, branchId, "counter", "unused", "Counter", UserRole.EMPLOYEE, true);
        mvc.perform(put("/api/v1/tables/" + table).with(user(employee)).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tables").with(user(employee)).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/tables/" + otherTable).with(user(owner())).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isNotFound());
        assertThat(floorTable(table).get("isPremium").asBoolean()).isFalse();
        for (String path : new String[]{"/reports/daily?date=2026-09-25", "/reports/period?from=2026-09-25&to=2026-09-25"}) {
            mvc.perform(get("/api/v1" + path).with(user(employee))).andExpect(status().isForbidden());
            JsonNode report = getData(path);
            JsonNode rows = report.get(path.contains("daily") ? "tableUtilisation" : "tables");
            assertThat(rows.size()).isEqualTo(1);
            assertThat(rows.get(0).get("tableId").asText()).isEqualTo(table.toString());
            assertThat(rows.get(0).get("isPremium").asBoolean()).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT is_premium FROM pool_table WHERE id=?", Boolean.class, otherTable)).isTrue();
    }

    private void edit(UUID id, String rate, String premium) throws Exception {
        mvc.perform(put("/api/v1/tables/" + id).with(user(owner())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Edited table\"," + rate + premium + "}")).andExpect(status().isOk());
        detach();
    }
    private JsonNode floorTable(UUID id) throws Exception {
        for (JsonNode table : getData("/tables").get("tables")) if (table.get("id").asText().equals(id.toString())) return table;
        throw new AssertionError("Table missing after read-back: " + id);
    }
    private String rateRows(UUID id) {
        return jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(r) ORDER BY id)::text FROM pool_table_rate r WHERE pool_table_id=?", String.class, id);
    }
    private JsonNode getData(String path) throws Exception { return data(mvc.perform(get("/api/v1" + path).with(user(owner()))).andExpect(status().isOk())); }
    private JsonNode postData(String path, String body) throws Exception {
        JsonNode result = data(mvc.perform(post("/api/v1" + path).with(user(owner())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()));
        detach(); return result;
    }
    private JsonNode data(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data"); }
    private void detach() { em.flush(); em.clear(); }
    private AppUserDetails owner() { return new AppUserDetails(ownerId, branchId, "owner", "unused", "Owner", UserRole.ADMIN, true); }
}
