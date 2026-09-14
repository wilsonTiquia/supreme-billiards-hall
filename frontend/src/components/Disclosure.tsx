import { useId, useState, type ReactNode } from 'react';

/**
 * A collapsed section with its one key figure on the closed header.
 *
 * The owner's two reading pages put everything past the headline behind one of these, so the
 * first screen is only what the ten-second test needs and the rest is a glance at the header
 * — "Given away — ₱1,823" — and a click for the breakdown.
 *
 * A button and a div rather than <details>, for one reason: print. A closed <details> prints
 * nothing, and the report is the page that gets printed. Here the body is always in the DOM
 * and only hidden on screen, so the printed report carries every section open.
 */
export function Disclosure({
  title,
  summary,
  tone,
  defaultOpen = false,
  children,
}: {
  title: string;
  /** The key figure, shown beside the title while the section is closed and open alike. */
  summary?: ReactNode;
  /** Only when the figure itself is a warning. Neutral by default — most figures are facts. */
  tone?: 'danger';
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const bodyId = useId();

  return (
    <section className="border-b border-border print:border-0">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-controls={bodyId}
        className="hit flex w-full items-center gap-3 py-3 text-left print:hidden"
      >
        <span
          aria-hidden
          className={`inline-block text-label text-text-dim transition-transform ${open ? 'rotate-90' : ''}`}
        >
          ▸
        </span>
        <span className="flex-1 text-body text-text">{title}</span>
        {summary !== undefined ? (
          <span className={`tabular text-body ${tone === 'danger' ? 'text-danger' : 'text-text-dim'}`}>
            {summary}
          </span>
        ) : null}
      </button>
      {/* The printed header: the same title and figure, as a heading rather than a control. */}
      <h2 className="hidden print:block print:pt-4 text-heading text-text">
        {title}
        {summary !== undefined ? <span className="text-text-dim"> — {summary}</span> : null}
      </h2>
      <div id={bodyId} className={`${open ? 'block' : 'hidden'} pb-6 pl-6 print:block print:pl-0`}>
        {children}
      </div>
    </section>
  );
}
