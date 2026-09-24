import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { changeSetup, fetchSetup } from '@/api/endpoints/setup';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { SetupItem, SetupKind } from '@/api/types';
import { Button } from '@/components/Button';
import { Card } from '@/components/Card';
import { Banner } from '@/components/Banner';
import { Modal } from '@/components/Modal';
import { useToast } from '@/components/Toast';
import { formatDateTime } from '@/lib/datetime';

export function useSetupLifecycle(kind: SetupKind, onChanged: () => void) {
  const client = useQueryClient();
  const { notify } = useToast();
  const [showArchived, setShowArchived] = useState(false);
  const [selection, setSelection] = useState<{ item: SetupItem; action: 'delete' | 'archive' } | null>(null);
  const query = useQuery({ queryKey: queryKeys.setup(kind), queryFn: () => fetchSetup(kind) });
  const change = useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'delete' | 'archive' | 'restore'; name: string }) => changeSetup(kind, id, action),
    onSuccess: (_, { action, name }) => {
      setSelection(null);
      notify(`${name} ${action === 'delete' ? 'deleted' : action === 'archive' ? 'archived' : 'restored'}.${action === 'restore' && kind === 'staff' ? ' Edit the account to enable sign-in.' : ''}`);
      onChanged();
      void client.invalidateQueries({ queryKey: queryKeys.setup(kind) });
      void client.invalidateQueries({ queryKey: ['audit'] });
    },
    // Eligibility can change in another tab. Show the refusal and refresh the row action.
    onError: () => { void client.invalidateQueries({ queryKey: queryKeys.setup(kind) }); },
  });
  const archived = (query.data ?? []).filter((item) => item.archivedAt);

  return {
    toggle: (
      <label className="hit flex cursor-pointer items-center gap-2 text-body text-text-dim">
        <input type="checkbox" checked={showArchived} onChange={(event) => setShowArchived(event.target.checked)} className="size-5 accent-green" />
        Show archived{archived.length ? ` (${archived.length})` : ''}
      </label>
    ),
    action: (id: string, blockedReason?: string) => {
      const item = query.data?.find((row) => row.id === id);
      const reason = blockedReason ?? item?.blockedReason;
      return <Button variant="secondary" disabled={!item || Boolean(reason) || change.isPending}
        title={reason ?? item?.deletionReason ?? undefined}
        onClick={() => { if (item) { change.reset(); setSelection({ item, action: item.canDelete ? 'delete' : 'archive' }); } }}>
        {item ? (item.canDelete ? 'Delete' : 'Archive') : 'Checking…'}
      </Button>;
    },
    panel: (
      <>
        {query.isError ? <div className="mt-4"><Banner tone="danger" actions={<Button variant="secondary" onClick={() => void query.refetch()}>Retry</Button>}>{messageOf(query.error)}</Banner></div> : null}
        {change.isError && !selection ? <div className="mt-4"><Banner tone="danger">{messageOf(change.error)}</Banner></div> : null}
        {showArchived ? <Card className="mt-4">
          <h2 className="text-body font-semibold text-text">Archived</h2>
          {kind === 'staff' && archived.length ? <p className="mt-1 text-label text-text-dim">Restored staff remain inactive until you enable sign-in in Edit.</p> : null}
          {archived.length ? <ul className="mt-2 divide-y divide-border">{archived.map((item) => (
            <li key={item.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
              <div className="min-w-0 break-words"><div className="text-body text-text">{item.name}</div><div className="text-label text-text-dim">Archived {formatDateTime(item.archivedAt!)}</div></div>
              <Button variant="secondary" disabled={change.isPending} pending={change.isPending && change.variables?.id === item.id}
                onClick={() => { change.reset(); change.mutate({ id: item.id, action: 'restore', name: item.name }); }}>Restore</Button>
            </li>
          ))}</ul> : <p className="mt-3 text-body text-text-dim">No archived items.</p>}
        </Card> : null}
      </>
    ),
    dialog: selection ? (
      <Modal title={`${selection.action === 'delete' ? 'Delete' : 'Archive'} ${selection.item.name}?`} onClose={() => { if (!change.isPending) { setSelection(null); change.reset(); } }}>
        <div className="flex flex-col gap-5">
          <p className="text-body text-text-dim">{selection.action === 'delete'
            ? `This unused ${kind === 'vouchers' ? 'batch and all its codes' : 'item'} will be permanently deleted. This cannot be undone.`
            : kind === 'vouchers' ? 'Unused codes in this batch will stop working. Past redemptions are kept. You can restore the batch from Show archived.'
            : 'This item will leave the active list. Its history is kept, and you can restore it from Show archived.'}</p>
          {change.isError ? <Banner tone="danger">{messageOf(change.error)}</Banner> : null}
          <div className="flex flex-wrap justify-end gap-3">
            <Button variant="secondary" disabled={change.isPending} onClick={() => { setSelection(null); change.reset(); }}>Cancel</Button>
            <Button variant="danger" pending={change.isPending} onClick={() => change.mutate({ id: selection.item.id, name: selection.item.name, action: selection.action })}>
              {selection.action === 'delete' ? 'Delete permanently' : 'Archive'}
            </Button>
          </div>
        </div>
      </Modal>
    ) : null,
  };
}
