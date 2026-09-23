import type { ClientBase } from "pg";

// Three answers, not two: "nobody counted the drawer" and "counted but never closed" are
// different mistakes by different people, and the owner wants to know which one to ask about.
export type Night =
    | { state: "uncounted" }
    | { state: "counted-not-closed" }
    | { state: "closed"; closedAt: string };

// The cash_count row is the day-end record (V3__cash_count_close.sql): a night is closed iff its
// row exists with closed_at set. UNIQUE (branch_id, business_date) means zero rows or one.
export async function nightClosed(db: ClientBase, branchId: string, businessDate: string): Promise<Night> {
    const { rows } = await db.query<{ closed_at: string | null }>(
        `SELECT to_char(closed_at AT TIME ZONE 'Asia/Manila', 'YYYY-MM-DD HH24:MI') AS closed_at
           FROM cash_count
          WHERE branch_id = $1 AND business_date = $2::date`,
        [branchId, businessDate]);

    if (rows.length === 0) return { state: "uncounted" };
    const closedAt = rows[0].closed_at;
    return closedAt === null ? { state: "counted-not-closed" } : { state: "closed", closedAt };
}
