import type { ReactNode } from 'react';
import { Button } from './Button';
import { Icon } from './Icon';
import './controls.css';

/** Display pages are one-based; callers retain ownership of their API/URL indexing. */
export function Pagination({ label, page, pages, count, pending = false, onPrevious, onNext }: {
  label: string;
  page: number;
  pages: number;
  count: ReactNode;
  pending?: boolean;
  onPrevious: () => void;
  onNext: () => void;
}) {
  return <nav className="pagination" aria-label={label} aria-busy={pending || undefined}>
    <p className="pagination-count" role="status">{count}</p>
    <div className="pagination-controls">
      <Button type="button" variant="secondary" disabled={pending || page <= 1} onClick={onPrevious}>
        <Icon name="chevron-left" />Previous
      </Button>
      <span className="pagination-page" role="status">Page {page} of {Math.max(1, pages)}</span>
      <Button type="button" variant="secondary" disabled={pending || page >= pages} onClick={onNext}>
        Next<Icon name="chevron-right" />
      </Button>
    </div>
  </nav>;
}
