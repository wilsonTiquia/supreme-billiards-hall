package com.supremebilliardshall.billiards_hall_system.acceptance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VoucherCancellationMigrationTest {
    @Autowired DataSource dataSource;

    @Test
    void migrationPreservesEveryLegacyArchiveBlockAndLeavesActiveBatchesAlone() throws Exception {
        // Exercise the actual migration against pre-V25 table shapes in an isolated scratch schema.
        String schema = "voucher_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("SET search_path TO " + schema + ", public");
                sql.execute("CREATE TABLE voucher_batch (LIKE public.voucher_batch INCLUDING DEFAULTS)");
                sql.execute("ALTER TABLE voucher_batch DROP COLUMN cancelled_at");
                sql.execute("CREATE TABLE audit_log (LIKE public.audit_log INCLUDING DEFAULTS INCLUDING GENERATED)");
                sql.execute("INSERT INTO voucher_batch(id,branch_id,minutes,quantity,expires_on,note,created_by,created_at,archived_at) "
                        + "SELECT uuidv7(),uuidv7(),120,2,DATE '2030-01-01',x,uuidv7(),now(),CASE WHEN x='legacy' THEN TIMESTAMPTZ '2026-09-01 10:00:00+08' ELSE NULL END FROM (VALUES ('legacy'),('active')) v(x)");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V25__voucher_batch_cancellation.sql"));
                try (var rows = sql.executeQuery("SELECT note,archived_at,cancelled_at FROM voucher_batch ORDER BY note")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("active");
                    assertThat(rows.getObject(2)).isNull();
                    assertThat(rows.getObject(3)).isNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("legacy");
                    assertThat(rows.getObject(2)).isNotNull().isEqualTo(rows.getObject(3));
                }
                try (var rows = sql.executeQuery("SELECT actor_id,action,note,after->>'cancelledAt' FROM audit_log")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(1)).isNull();
                    assertThat(rows.getString(2)).isEqualTo("VOUCHER_BATCH_CANCELLED");
                    assertThat(rows.getString(3)).contains("Migration V25", "no codes reactivated");
                    assertThat(rows.getString(4)).isNotNull();
                    assertThat(rows.next()).isFalse();
                }
                // The existing Restore operation must not clear the migrated block.
                sql.execute("UPDATE voucher_batch SET archived_at=NULL WHERE note='legacy'");
                try (var rows = sql.executeQuery("SELECT cancelled_at FROM voucher_batch WHERE note='legacy'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(1)).isNotNull();
                }
            } finally {
                sql.execute("SET search_path TO public");
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
