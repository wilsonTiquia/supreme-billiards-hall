package com.supremebilliardshall.billiards_hall_system.acceptance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PoolTablePremiumMigrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void actualMigrationBackfillsOnlyVerifiedIdsPreservesRatesAndDefaultsOtherAndNewTables() {
        // A transaction-local copy of the two real tables, with the new column removed,
        // exercises the checked-in migration without modifying the application's schema.
        String schema = "premium_migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("CREATE TABLE " + schema + ".pool_table (LIKE public.pool_table INCLUDING ALL)");
        jdbc.execute("ALTER TABLE " + schema + ".pool_table DROP COLUMN is_premium");
        jdbc.execute("INSERT INTO " + schema + ".pool_table SELECT id,branch_id,name,table_number,is_active,archived_at,created_at,updated_at FROM public.pool_table");
        jdbc.execute("CREATE TABLE " + schema + ".pool_table_rate (LIKE public.pool_table_rate INCLUDING ALL)");
        jdbc.execute("INSERT INTO " + schema + ".pool_table_rate SELECT * FROM public.pool_table_rate");
        jdbc.execute("SET LOCAL search_path TO " + schema + ", public");
        UUID other = jdbc.queryForObject("INSERT INTO pool_table(branch_id,name,table_number) VALUES (?, 'Table 1 (Premium)', 1) RETURNING id", UUID.class, UUID.randomUUID());
        jdbc.update("UPDATE pool_table SET archived_at=now() WHERE id='019a0000-0000-7000-8000-000000000003'");
        String rates = jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(r) ORDER BY id)::text FROM pool_table_rate r", String.class);
        String tables = jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM pool_table t", String.class);
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V23__pool_table_premium.sql"));
            return null;
        });
        assertThat(jdbc.queryForList("SELECT id::text FROM pool_table WHERE is_premium ORDER BY id", String.class)).isEqualTo(List.of(
                "019a0000-0000-7000-8000-000000000001", "019a0000-0000-7000-8000-000000000002", "019a0000-0000-7000-8000-000000000003"));
        assertThat(jdbc.queryForObject("SELECT is_premium FROM pool_table WHERE id=?", Boolean.class, other)).isFalse();
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(r) ORDER BY id)::text FROM pool_table_rate r", String.class)).isEqualTo(rates);
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(t)-'is_premium' ORDER BY id)::text FROM pool_table t", String.class)).isEqualTo(tables);
        Boolean newFlag = jdbc.queryForObject("INSERT INTO pool_table(branch_id,name,table_number) VALUES (?, 'New premium-sounding table', 2) RETURNING is_premium", Boolean.class, UUID.randomUUID());
        assertThat(newFlag).isFalse();
    }
}
