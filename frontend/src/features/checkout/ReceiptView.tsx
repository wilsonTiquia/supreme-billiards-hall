import type { ReactNode, Ref } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchReceipt } from '@/api/endpoints/bills';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import { Banner } from '@/components/Banner';
import { Button } from '@/components/Button';
import { Card } from '@/components/Card';
import { Spinner } from '@/components/Spinner';
import { NoteThread } from '@/features/notes/NoteThread';
import { ReceiptDocument } from './ReceiptDocument';
import './receipt.css';

export function ReceiptView({ billId, back, cardRef, children }: {
  billId: string;
  back: ReactNode;
  cardRef?: Ref<HTMLDivElement>;
  children?: ReactNode;
}) {
  const receipt = useQuery({
    queryKey: queryKeys.receipt(billId),
    queryFn: () => fetchReceipt(billId),
    staleTime: Infinity,
  });

  return (
    <div className="receipt-workspace">
      <div className="receipt-toolbar print:hidden">
        {back}
        <Button variant="secondary" disabled={!receipt.isSuccess} onClick={() => window.print()}>Print</Button>
      </div>
      {receipt.isPending ? (
        <div className="flex justify-center py-16"><Spinner label="Loading the receipt…" /></div>
      ) : receipt.isError ? (
        <Card>
          <Banner tone="danger">{messageOf(receipt.error)}</Banner>
          <Button variant="secondary" className="mt-4" pending={receipt.isFetching} onClick={() => void receipt.refetch()}>Retry</Button>
        </Card>
      ) : (
        <div className="receipt-layout">
          <ReceiptDocument receipt={receipt.data} cardRef={cardRef} />
          <aside aria-label="Receipt notes" className="receipt-notes print:hidden">
            <Card><NoteThread source={{ kind: 'bill', billId }} title="Notes" /></Card>
            {children}
          </aside>
        </div>
      )}
    </div>
  );
}
