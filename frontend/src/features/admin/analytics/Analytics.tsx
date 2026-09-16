import type { ReactNode } from 'react';
import { ComparisonLine } from '../Comparison';
import './analytics.css';

export function AnalyticsPanel({
  title,
  subtitle,
  action,
  children,
  className = '',
}: {
  title: string;
  subtitle?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`analytics-panel ${className}`}>
      <header className="analytics-panel-header">
        <div>
          <h2>{title}</h2>
          {subtitle && <p>{subtitle}</p>}
        </div>
        {action}
      </header>
      {children}
    </section>
  );
}

export function Stat({
  label,
  value,
  note,
  comparison,
  primary = false,
  danger = false,
}: {
  label: string;
  value: string;
  note?: string;
  comparison?: Parameters<typeof ComparisonLine>[0];
  primary?: boolean;
  danger?: boolean;
}) {
  return (
    <section className={`analytics-stat ${primary ? 'analytics-stat-primary' : ''}`}>
      <h2>{label}</h2>
      <p className={`analytics-value ${danger ? 'text-danger' : ''}`}>{value}</p>
      {comparison &&
        (comparison.before === 0 ? (
          <p className="analytics-note">No nonzero prior value to compare</p>
        ) : (
          <ComparisonLine {...comparison} />
        ))}
      {note && <p className="analytics-note">{note}</p>}
    </section>
  );
}

export function AnalyticsLoading({ label }: { label: string }) {
  return (
    <div role="status" aria-label={label} className="analytics-loading">
      <span className="sr-only">{label}</span>
      <div className="analytics-stats">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="analytics-skeleton" />
        ))}
      </div>
      <div className="analytics-skeleton analytics-skeleton-chart" />
    </div>
  );
}

export function EmptyState({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="analytics-empty">
      <span aria-hidden className="analytics-empty-mark">
        ◇
      </span>
      <h3>{title}</h3>
      <p>{children}</p>
    </div>
  );
}

/** Values remain visible without hover, and bars only encode proportions of API figures. */
export function RankedBars({
  rows,
  empty = 'No activity recorded for this period.',
}: {
  rows: { label: string; value: number; display: string; detail?: string }[];
  empty?: string;
}) {
  const max = Math.max(0, ...rows.map((row) => row.value));
  if (!rows.length) return <EmptyState title="No activity yet">{empty}</EmptyState>;
  return (
    <ol className="analytics-ranks">
      {rows.map((row, index) => (
        <li key={`${row.label}-${index}`}>
          <div className="analytics-rank-label">
            <span>
              <b className="analytics-rank-number">{String(index + 1).padStart(2, '0')}</b>
              {row.label}
            </span>
            <strong>{row.display}</strong>
          </div>
          <div className="analytics-track" aria-hidden>
            <div style={{ width: `${max > 0 ? Math.max(0, (row.value / max) * 100) : 0}%` }} />
          </div>
          {row.detail && <p className="analytics-note">{row.detail}</p>}
        </li>
      ))}
    </ol>
  );
}
