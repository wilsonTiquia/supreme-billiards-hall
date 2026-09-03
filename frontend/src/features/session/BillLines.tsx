import { useState } from 'react';
import type { BillLine } from '@/api/types';
import { splitBillLines } from '@/lib/billLines';
import { formatMoney } from '@/lib/money';

/**
 * Identical lines read as one row — "4 × San Miguel Pale Pilsen" rather than four rows of one.
 * The underlying bill_line rows are untouched; see lib/billLines.ts.
 *
 * Voided lines stay on the list, struck through, individually, with their reason, and excluded
 * from the total. Removing or folding them would hide what actually happened at the counter,
 * which is the opposite of the point.
 */
export function BillLines({
  lines,
  onVoid,
}: {
  lines: BillLine[];
  onVoid: (line: BillLine) => void;
}) {
  // Which grouped rows the operator has opened up to void one of. Keyed by group, so adding
  // another of the same drink does not collapse a row mid-decision.
  const [expanded, setExpanded] = useState<string[]>([]);

  if (lines.length === 0) {
    return <p className="py-6 text-center text-body text-text-dim">Nothing rung up yet.</p>;
  }

  const { timeLines, itemGroups, voidedItems } = splitBillLines(lines);

  return (
    <ul className="divide-y divide-border">
      {timeLines.map((line) => (
        <li key={line.id} className="flex items-start justify-between gap-3 py-3">
          <div>
            <div className="text-body text-text">{line.description}</div>
            <div className="tabular text-label text-text-dim">Table time</div>
          </div>
          {/* A TIME line goes with its session; it is never voided on its own. */}
          <span className="tabular text-body text-text">{formatMoney(line.lineTotal)}</span>
        </li>
      ))}

      {itemGroups.map((group) => {
        const single = group.members.length === 1;
        const open = expanded.includes(group.key);

        // One row, or the physical rows behind it once the operator asks to void one.
        if (single || !open) {
          return (
            <li key={group.key} className="flex items-start justify-between gap-3 py-3">
              <div>
                <div className="text-body text-text">{group.description}</div>
                <div className="tabular text-label text-text-dim">
                  {group.quantity} × {formatMoney(group.unitPrice)}
                </div>
              </div>

              <div className="flex items-center gap-3">
                <span className="tabular text-body text-text">
                  {formatMoney(group.lineTotal)}
                </span>
                {/* Kept well away from the product grid so it is never a mis-tap. On a grouped
                    row this opens the row rather than guessing which one was meant. */}
                <button
                  type="button"
                  onClick={() =>
                    single ? onVoid(group.members[0]) : setExpanded((k) => [...k, group.key])
                  }
                  className="hit shrink-0 rounded-lg px-3 text-label uppercase text-text-dim transition hover:bg-raised hover:text-danger"
                >
                  {single ? 'Void' : 'Void one…'}
                </button>
              </div>
            </li>
          );
        }

        return (
          <li key={group.key} className="py-3">
            <div className="flex items-baseline justify-between gap-3">
              <div className="text-body text-text">{group.description}</div>
              <button
                type="button"
                onClick={() => setExpanded((keys) => keys.filter((k) => k !== group.key))}
                className="hit shrink-0 rounded-lg px-3 text-label uppercase text-text-dim transition hover:bg-raised hover:text-text"
              >
                Done
              </button>
            </div>
            <p className="text-label text-text-dim">Choose which one to void.</p>
            <ul className="mt-2 divide-y divide-border border-t border-border">
              {group.members.map((line) => (
                <li key={line.id} className="flex items-center justify-between gap-3 py-2 pl-3">
                  {/* The bill's own line number, so it is unambiguous which row is going. */}
                  <span className="tabular text-label text-text-dim">
                    Line {line.seq} · {line.quantity} × {formatMoney(line.unitPrice)}
                  </span>
                  <div className="flex items-center gap-3">
                    <span className="tabular text-body text-text">
                      {formatMoney(line.lineTotal)}
                    </span>
                    <button
                      type="button"
                      onClick={() => onVoid(line)}
                      className="hit shrink-0 rounded-lg px-3 text-label uppercase text-text-dim transition hover:bg-raised hover:text-danger"
                    >
                      Void
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          </li>
        );
      })}

      {voidedItems.map((line) => (
        <li key={line.id} className="flex items-start justify-between gap-3 py-3">
          <div className="opacity-60">
            <div className="text-body text-text line-through">{line.description}</div>
            <div className="tabular text-label text-text-dim">
              {line.lineKind === 'TIME'
                ? 'Table time'
                : `${line.quantity} × ${formatMoney(line.unitPrice)}`}
            </div>
            <div className="text-label text-danger">Voided — {line.voidReason}</div>
          </div>
          <span className="tabular text-body text-text-dim line-through">
            {formatMoney(line.lineTotal)}
          </span>
        </li>
      ))}
    </ul>
  );
}
