import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { Button } from '@/components/Button';
import { Icon } from '@/components/Icon';
import { ReceiptView } from './ReceiptView';

/** Native modality makes the Sales list inert and keeps keyboard focus inside the receipt. */
export function ReceiptDialog() {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const { billId = '' } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const fromSales = location.state?.salesReceipt === true;
  const close = () => {
    // Links from Sales push one history entry. A cold URL has no list entry to go back to.
    if (fromSales) navigate(-1);
    else navigate(`/admin/sales${location.search}`, { replace: true });
  };

  useEffect(() => {
    const dialog = dialogRef.current!;
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
    <dialog ref={dialogRef} className="receipt-dialog" aria-label="Sales receipt"
      onKeyDown={event => {
        if (event.key !== 'Tab') return;
        const controls = Array.from(event.currentTarget.querySelectorAll<HTMLElement>(
          'button:not([disabled]), a[href], input:not([disabled]), [tabindex="0"]',
        ));
        const first = controls[0];
        const last = controls[controls.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }}
      onCancel={event => { event.preventDefault(); close(); }}
      onClick={event => {
        if (event.target !== event.currentTarget) return;
        const rect = event.currentTarget.getBoundingClientRect();
        if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) close();
      }}>
      <ReceiptView key={billId} billId={billId}
        back={<Button variant="secondary" onClick={close}><Icon name="back" />Close</Button>} />
    </dialog>,
    document.body,
  );
}
