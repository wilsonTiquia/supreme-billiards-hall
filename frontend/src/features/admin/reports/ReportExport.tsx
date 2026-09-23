import { useId, useRef, useState } from 'react';
import type { PeriodReport } from '@/api/types';
import { downloadReport } from './reportData';
import { reportView, type ExplorerView } from './reportView';

export function ReportExport({ data, view, disabled }: { data?: PeriodReport; view: ExplorerView; disabled: boolean }) {
  const id = useId();
  const [position, setPosition] = useState({ top: 0, left: 0 });
  const panel = useRef<HTMLDivElement>(null);
  const dataset = data ? reportView(data, view) : null;
  return <div className="reports-export">
    <button type="button" className="analytics-button" popoverTarget={id} disabled={disabled} onClick={(event) => {
      const rect = event.currentTarget.getBoundingClientRect();
      setPosition({ top: rect.bottom + 8, left: Math.max(16, Math.min(rect.left, window.innerWidth - 336)) });
    }}>Export ▾</button>
    <div ref={panel} id={id} popover="auto" className="reports-export-menu" style={position} aria-label="Export report">
      <p>Export report</p>
      <button type="button" onClick={() => { panel.current?.hidePopover(); window.print(); }}>PDF · print full report</button>
      <button type="button" disabled={!dataset?.rows.length} onClick={() => {
        if (data && dataset) downloadReport(dataset, `supreme-${view.kind}-${data.from}-to-${data.to}.csv`);
        panel.current?.hidePopover();
      }}>CSV · {view.kind} ({dataset?.rows.length ?? 0} rows)</button>
      <small>CSV includes all filtered rows in the selected tab.</small>
    </div>
  </div>;
}
