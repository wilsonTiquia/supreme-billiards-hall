import { useEffect, useId, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { formatMoney } from '@/lib/money';

/** Hover is temporary; touch/select stays open until dismissed. One keyboard stop per chart. */
export function useChartSelection(count: number) {
  const [hovered, setHovered] = useState<number | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [focusIndex, setFocusIndex] = useState(0);
  useEffect(() => {
    const escape = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') { setHovered(null); setSelected(null); }
    };
    document.addEventListener('keydown', escape);
    return () => document.removeEventListener('keydown', escape);
  }, []);
  const active = hovered ?? selected;
  const dismiss = () => { setHovered(null); setSelected(null); };
  const onKeyDown = (event: KeyboardEvent<SVGGElement>, index: number) => {
    if (event.key === 'Escape') { dismiss(); return; }
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? count - 1
      : event.key === 'ArrowRight' ? Math.min(count - 1, index + 1)
      : event.key === 'ArrowLeft' ? Math.max(0, index - 1) : null;
    if (next !== null) {
      event.preventDefault();
      const bars = event.currentTarget.parentElement?.querySelectorAll<SVGGElement>('[data-chart-bar]');
      bars?.[next]?.focus();
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault(); setSelected(index);
    }
  };
  return {
    active: active !== null && active < count ? active : null,
    select: (index: number | null) => { setHovered(null); setSelected(index); },
    dismiss,
    barProps: (index: number) => ({
      'data-chart-bar': true,
      role: 'button',
      tabIndex: Math.min(focusIndex, count - 1) === index ? 0 : -1,
      onPointerMove: (event: PointerEvent) => { if (event.pointerType === 'mouse') setHovered(index); },
      onPointerLeave: () => setHovered(null),
      onFocus: () => { setHovered(null); setFocusIndex(index); setSelected(index); },
      onBlur: dismiss,
      onClick: () => setSelected(index),
      onKeyDown: (event: KeyboardEvent<SVGGElement>) => onKeyDown(event, index),
    }),
  };
}

export function ChartTooltip({ id, x, y, label, amount, bills }: {
  id: string; x: number; y: number; label: string; amount: number; bills?: number;
}) {
  return <div id={id} role="tooltip" className="analytics-chart-tooltip"
    style={{ left: `clamp(110px, ${x}%, calc(100% - 110px))`, top: `max(80px, ${y}%)` }}>
    <span>{label}</span>
    <strong>{formatMoney(amount)}</strong>
    {bills !== undefined && <span>{bills} {bills === 1 ? 'bill' : 'bills'}</span>}
  </div>;
}

/** A full-size touch alternative for dense charts, also useful to screen-reader users. */
export function ChartPicker({ label, options, active, onSelect }: {
  label: string; options: string[]; active: number | null; onSelect: (index: number | null) => void;
}) {
  const id = useId();
  return <div className="analytics-chart-picker">
    <label htmlFor={id}>{label}</label>
    <select id={id} value={active ?? ''} onChange={event => onSelect(event.target.value === '' ? null : Number(event.target.value))}
      onKeyDown={event => { if (event.key === 'Escape') onSelect(null); }}>
      <option value="">Choose to see exact values</option>
      {options.map((option, index) => <option key={index} value={index}>{option}</option>)}
    </select>
  </div>;
}

export function ChartFigures({ label, rows }: { label: string; rows: { label: string; amount: number; bills?: number }[] }) {
  return <table className="analytics-chart-figures">
    <caption>{label}</caption>
    <thead><tr><th scope="col">Night / hour</th><th scope="col">Sales</th><th scope="col">Bills</th></tr></thead>
    <tbody>{rows.map((row, index) => <tr key={index}><th scope="row">{row.label}</th><td>{formatMoney(row.amount)}</td><td>{row.bills ?? '—'}</td></tr>)}</tbody>
  </table>;
}
