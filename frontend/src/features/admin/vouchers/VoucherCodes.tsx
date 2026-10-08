import { useEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import type { Voucher, VoucherBatch } from '@/api/types';
import { Button } from '@/components/Button';
import { Icon } from '@/components/Icon';
import { formatBusinessDate } from '@/lib/datetime';

/** Native modality keeps the background inert; the body portal also isolates printed codes. */
export function VoucherCodesDialog({ title, batch, onClose, children }: {
  title: string; batch: VoucherBatch; onClose: () => void; children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current!;
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    dialog.showModal();
    dialog.querySelector<HTMLButtonElement>('button')?.focus();
    return () => {
      dialog.close();
      document.body.style.overflow = overflow;
      if (previous?.isConnected) previous.focus({ preventScroll: true });
    };
  }, []);
  return createPortal(
    <dialog ref={ref} className="voucher-dialog" aria-label={title}
      onCancel={event => { event.preventDefault(); onClose(); }}
      onClick={event => {
        if (event.target !== event.currentTarget) return;
        const rect = event.currentTarget.getBoundingClientRect();
        if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) onClose();
      }}>
      <div className="mb-4 flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-heading text-text">{title}</h2>
          <p className="text-body text-text-dim">{batch.hoursLabel} per coupon</p>
        </div>
        <Button variant="tertiary" className="voucher-no-print shrink-0" aria-label="Close" onClick={onClose}><Icon name="close" /></Button>
      </div>
      {batch.note ? <p className="mb-2 break-words text-body font-semibold">{batch.note}</p> : null}
      <p className="mb-4 text-label text-text-dim">Expires {formatBusinessDate(batch.expiresOn)}</p>
      {children}
    </dialog>, document.body,
  );
}

/** Status is server supplied and read-only. Every code remains selectable and on one line. */
export function VoucherCodeList({ codes }: { codes: Voucher[] }) {
  const [copyState, setCopyState] = useState('');
  const [copying, setCopying] = useState(false);
  async function copy() {
    setCopying(true);
    try {
      await navigator.clipboard.writeText(codes.map(voucher => voucher.code).join('\n'));
      setCopyState('All codes copied.');
    } catch {
      setCopyState('Could not copy. Select the codes to copy them manually.');
    } finally { setCopying(false); }
  }
  if (codes.length === 0) return <p className="py-6 text-center text-body text-text-dim">No codes in this batch.</p>;
  return <>
    <div className="voucher-no-print mb-3 flex flex-wrap gap-2">
      <Button variant="secondary" onClick={() => void copy()} pending={copying}>Copy all codes</Button>
      <Button variant="secondary" onClick={() => window.print()}>Print codes</Button>
    </div>
    <p className="voucher-no-print mb-3 text-label text-text-dim">Checked means redeemed. Status is read-only.</p>
    <p role="status" aria-label="Copy result" className="voucher-no-print text-label text-text-dim">{copyState}</p>
    <div className="voucher-code-scroll" role="region" aria-label="Voucher codes" tabIndex={0}>
      <ul className="voucher-code-list">
        {codes.map(voucher => <li key={voucher.id} className="voucher-code-row">
          <span aria-hidden="true" className={`voucher-check ${voucher.status === 'REDEEMED' ? 'text-green' : 'text-text-dim'}`}>
            {voucher.status === 'REDEEMED' ? <Icon name="check" /> : null}
          </span>
          <div className="min-w-0">
            <div className="voucher-code-value" tabIndex={0} role="region" aria-label={`Code ${voucher.code}`}>
              <code>{voucher.code}</code>
            </div>
            <p className={`text-body ${voucher.status === 'REDEEMED' ? 'text-green' : voucher.status === 'EXPIRED' ? 'text-[var(--warning)]' : 'text-text-dim'}`}>
              {voucher.status === 'REDEEMED'
                ? voucher.redeemedReceiptNo != null ? `Redeemed on receipt #${voucher.redeemedReceiptNo}` : 'Redeemed · receipt not yet available'
                : voucher.status === 'EXPIRED' ? 'Expired' : 'Outstanding'}
            </p>
          </div>
        </li>)}
      </ul>
    </div>
  </>;
}
