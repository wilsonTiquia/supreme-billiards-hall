package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.dto.setup.SetupItemResponseDTO;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.SetupKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class SetupRepository {
    private final JdbcTemplate jdbc;
    private final BranchContext branchContext;

    public SetupRepository(JdbcTemplate jdbc, BranchContext branchContext) {
        this.jdbc = jdbc;
        this.branchContext = branchContext;
    }

    // All identifiers come from our enum or PostgreSQL's catalog, never from a request.
    private String scope(SetupKind kind) {
        return kind == SetupKind.STAFF
                ? "(t.branch_id = ? OR (t.branch_id IS NULL AND t.role = 'ADMIN'))"
                : "t.branch_id = ?";
    }

    public List<SetupItemResponseDTO> list(SetupKind kind) {
        String sql = "SELECT t.id, " + kind.getLabel() + " AS label, t.archived_at, ("
                + references(kind) + ") AS used FROM " + kind.getTable() + " t WHERE "
                + scope(kind) + " ORDER BY t.archived_at NULLS FIRST, label, t.id";
        return jdbc.query(sql, (rs, n) -> new SetupItemResponseDTO(rs.getObject("id", UUID.class),
                rs.getString("label"), rs.getObject("archived_at", OffsetDateTime.class),
                !rs.getBoolean("used"), rs.getBoolean("used") ? reason(kind) : null, null),
                branchContext.getCurrentBranchId());
    }

    public SetupItemResponseDTO lock(SetupKind kind, UUID id) {
        var ids = jdbc.queryForList("SELECT t.id FROM " + kind.getTable() + " t WHERE "
                + scope(kind) + " AND t.id = ? FOR UPDATE", UUID.class,
                branchContext.getCurrentBranchId(), id);
        if (ids.isEmpty()) throw new ResourceNotFoundException("Setup item", id);
        return list(kind).stream().filter(row -> row.getId().equals(id)).findFirst().orElseThrow();
    }

    private String reason(SetupKind kind) {
        return switch (kind) {
            case CATEGORIES -> "Products have used this category. Archive it to keep their history.";
            case TABLES -> "This table has session history. Archive it to keep past bills.";
            case CUSTOMER_TYPES -> "Bills or sessions use this customer type. Archive it to keep their history.";
            case EXPENSE_CATEGORIES -> "Expenses use this category. Archive it to keep those records.";
            case VOUCHERS -> "A code in this batch has been used. Archive the batch to keep its history.";
            case STAFF -> "Recorded activity references this person. Archive the account to keep that history.";
        };
    }

    // Staff alone has over twenty inbound foreign keys. Reading the FK catalog keeps the
    // eligibility check in step with migrations, including archived/voided historical rows.
    // Child rates and generated codes belong to the unused parent and are removed with it.
    private String references(SetupKind kind) {
        List<String> checks = jdbc.query("""
                SELECT quote_ident(ns.nspname) || '.' || quote_ident(rel.relname) AS relation,
                       quote_ident(local.attname) AS column_name, rel.relname AS table_name
                FROM pg_constraint fk
                JOIN pg_class rel ON rel.oid = fk.conrelid
                JOIN pg_namespace ns ON ns.oid = rel.relnamespace
                CROSS JOIN LATERAL unnest(fk.conkey, fk.confkey) AS keys(local_key, foreign_key)
                JOIN pg_attribute local ON local.attrelid = fk.conrelid AND local.attnum = keys.local_key
                JOIN pg_attribute remote ON remote.attrelid = fk.confrelid AND remote.attnum = keys.foreign_key
                WHERE fk.contype = 'f' AND fk.confrelid = CAST(? AS regclass) AND remote.attname = 'id'
                """, (rs, n) -> {
            String child = rs.getString("table_name");
            if (kind == SetupKind.TABLES && child.equals("pool_table_rate")
                    || kind == SetupKind.VOUCHERS && child.equals("voucher")) return "false";
            return "EXISTS (SELECT 1 FROM " + rs.getString("relation") + " r WHERE r."
                    + rs.getString("column_name") + " = t.id)";
        }, kind.getTable());
        if (kind == SetupKind.CATEGORIES) checks.add("t.referenced_at IS NOT NULL");
        if (kind == SetupKind.STAFF) checks.add("t.last_login_at IS NOT NULL");
        if (kind == SetupKind.VOUCHERS) {
            // A released voucher is still historically used, even after both bill FKs clear.
            checks.add("""
                    EXISTS (SELECT 1 FROM voucher v WHERE v.batch_id = t.id AND
                      (v.redeemed_at IS NOT NULL
                       OR EXISTS (SELECT 1 FROM bill b WHERE b.voucher_id = v.id)
                       OR EXISTS (SELECT 1 FROM audit_log a WHERE a.branch_id = v.branch_id
                          AND a.action = 'VOUCHER_REDEEMED' AND a.after->>'code' = v.code)))
                    """);
        }
        return checks.isEmpty() ? "false" : checks.stream().collect(Collectors.joining(" OR "));
    }

    public void delete(SetupKind kind, UUID id) {
        if (kind == SetupKind.TABLES) jdbc.update("DELETE FROM pool_table_rate WHERE pool_table_id = ?", id);
        if (kind == SetupKind.VOUCHERS) jdbc.update("DELETE FROM voucher WHERE batch_id = ?", id);
        jdbc.update("DELETE FROM " + kind.getTable() + " WHERE id = ?", id);
    }

    public void archiveBatch(UUID id) {
        jdbc.update("UPDATE voucher_batch SET archived_at = now() WHERE id = ?", id);
    }

    public boolean nameTaken(SetupKind kind, UUID id) {
        if (kind == SetupKind.VOUCHERS) return false;
        String name = kind == SetupKind.STAFF ? "username" : "name";
        String branch = kind == SetupKind.STAFF ? "true" : "live.branch_id = archived.branch_id";
        String collision = "lower(live." + name + ") = lower(archived." + name + ")";
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + kind.getTable()
                + " archived JOIN " + kind.getTable() + " live ON " + branch
                + " WHERE archived.id = ? AND live.id <> archived.id AND live.archived_at IS NULL AND ("
                + collision + "))", Boolean.class, id));
    }

    public void restore(SetupKind kind, UUID id) {
        String extra = kind == SetupKind.CUSTOMER_TYPES
                ? ", is_default = CASE WHEN EXISTS (SELECT 1 FROM customer_type c WHERE c.branch_id = t.branch_id AND c.archived_at IS NULL AND c.is_default) THEN false ELSE t.is_default END"
                : "";
        // Staff remains inactive: restore makes the row manageable; Edit explicitly enables login.
        jdbc.update("UPDATE " + kind.getTable() + " t SET archived_at = NULL" + extra + " WHERE t.id = ?", id);
    }
}
