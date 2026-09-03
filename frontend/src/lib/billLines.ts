import type { BillLine, Money, Quantity } from '@/api/types';

/**
 * Grouping identical lines for DISPLAY. The data is never touched.
 *
 * Four separate `bill_line` rows for the same beer at the same price read badly on a screen —
 * the counter wants "4 × San Miguel Pale Pilsen". But those four rows stay four rows in the
 * database on purpose: each is an individually voidable record with its own actor, its own
 * timestamp and its own sequence number, and merging them would destroy that.
 *
 * So this folds them at render time and keeps the physical rows on the group, which is what
 * lets a void still name exactly which one it is acting on.
 *
 * Two things are deliberately NOT grouped:
 *   - Voided lines. A void is an event someone has to see, with its reason attached. Folding
 *     one into a quantity would hide the very thing the retention rule exists to show.
 *   - TIME lines. Each describes one session's own charge and its own rate; there is no such
 *     thing as two identical ones.
 *
 * On frontend/CLAUDE.md §2: the subtotal on a group is an aggregate of figures the server
 * already computed, shown beside them. Nothing charged is derived here — the bill total, both
 * subtotals and every individual line total still come from the server, and checkout still
 * sends the server's own `totalAmount`.
 */

export interface GroupableLine {
  description: string;
  unitPrice: Money;
  quantity: Quantity;
  lineTotal: Money;
}

export interface LineGroup<T extends GroupableLine> {
  key: string;
  description: string;
  unitPrice: Money;
  quantity: Quantity;
  lineTotal: Money;
  /** The physical rows behind the group, in the order they were rung up. */
  members: T[];
}

/** Same product at the same price. Both are snapshots, so this cannot fold two real prices. */
function keyOf(line: GroupableLine): string {
  return `${line.description}|${line.unitPrice}`;
}

export function groupLines<T extends GroupableLine>(lines: T[]): LineGroup<T>[] {
  const groups: LineGroup<T>[] = [];
  const byKey = new Map<string, LineGroup<T>>();

  for (const line of lines) {
    const key = keyOf(line);
    const existing = byKey.get(key);
    if (existing) {
      existing.quantity += line.quantity;
      existing.lineTotal += line.lineTotal;
      existing.members.push(line);
      continue;
    }
    // First of its kind holds the group's position, so the order rung up is preserved.
    const group: LineGroup<T> = {
      key,
      description: line.description,
      unitPrice: line.unitPrice,
      quantity: line.quantity,
      lineTotal: line.lineTotal,
      members: [line],
    };
    byKey.set(key, group);
    groups.push(group);
  }

  return groups;
}

/** Splits a bill's lines the way every display of them needs: grouped live, individual voided. */
export function splitBillLines(lines: BillLine[]): {
  timeLines: BillLine[];
  itemGroups: LineGroup<BillLine>[];
  voidedItems: BillLine[];
} {
  return {
    timeLines: lines.filter((line) => line.lineKind === 'TIME' && line.voidedAt === null),
    itemGroups: groupLines(
      lines.filter((line) => line.lineKind === 'PRODUCT' && line.voidedAt === null),
    ),
    voidedItems: lines.filter((line) => line.voidedAt !== null),
  };
}
