# Premium backfill verification

Verified 2 October 2026 against the existing **local** PostgreSQL `supreme` database, before writing V23. Connection used `PGOPTIONS='-c default_transaction_read_only=on'` and only SELECT. This was not a VPS inspection, migration or deployment.

```sql
SELECT t.id, t.branch_id, t.table_number, t.name, t.is_active, t.archived_at,
       r.rate_per_minute, r.rate_per_hour
FROM pool_table t
LEFT JOIN pool_table_rate r ON r.pool_table_id = t.id AND r.effective_to IS NULL
ORDER BY t.branch_id, t.table_number, t.id;
```

All seven rows belong to MAIN (`01900000-0000-7000-8000-000000000001`), are active and have no archived date. All current hourly input fields were NULL.

| Existing UUID | Number | Existing name | Current rate/minute | Approved classification |
|---|---|---|---|---|
| `019a0000-0000-7000-8000-000000000001` | 1 | Table 1 | 4.0000 | Premium |
| `019a0000-0000-7000-8000-000000000002` | 2 | Table 2 | 4.0000 | Premium |
| `019a0000-0000-7000-8000-000000000003` | 3 | Table 3 | 4.0000 | Premium |
| `019a0000-0000-7000-8000-000000000004` | 4 | Table 4 | 4.0000 | Standard |
| `019a0000-0000-7000-8000-000000000005` | 5 | Table 5 (Premium) | 5.0000 | Standard |
| `019a0000-0000-7000-8000-000000000006` | 6 | Table 6 (Premium) | 5.0000 | Standard |
| `019a0000-0000-7000-8000-000000000007` | 7 | Table 7 (Premium) | 5.0000 | Standard |

These identities also match the original V2 seed. The existing `(Premium)` name suffixes are preserved as catalog names; they do not control the flag, stars, rates or billing. Table administration explicitly shows Standard/Premium so the classification remains distinguishable from legacy names.

V23 adds `is_premium boolean NOT NULL DEFAULT false` and sets true only for the three verified UUIDs within MAIN. It never matches a mutable name, price or a table number in another branch. Other existing rows and future inserts default to false. Historical reports show current classification alongside unchanged historical financial/occupancy measures; classification is not backdated into receipts.

The migration regression runs the actual V23 script against transaction-local copies of the table/rate schema and records. It checks the exact three IDs, an archived target, a misleading premium name in another branch, future default inserts, and exact equality of every pre-existing non-classification field and rate row before/after the migration.
