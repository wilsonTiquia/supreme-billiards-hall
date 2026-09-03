import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchFloor } from '@/api/endpoints/tables';
import { fetchUnsettledBills } from '@/api/endpoints/bills';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { PoolTable } from '@/api/types';
import { useClock } from '@/time/useClock';
import { useTicker } from '@/time/useTicker';
import { useScreenTheme } from '@/app/useTheme';
import { TableCard } from './TableCard';
import { UnsettledStrip } from './UnsettledStrip';
import { StartSessionModal } from '@/features/session/StartSessionModal';
import { Banner } from '@/components/Banner';
import { Spinner } from '@/components/Spinner';

const POLL_MS = 10_000;

export function FloorPage() {
  useScreenTheme('pos');
  const navigate = useNavigate();
  const { syncTo } = useClock();
  const [starting, setStarting] = useState<PoolTable | null>(null);

  // Drives the local counters between polls. One interval for the whole floor.
  useTicker(true);

  const floor = useQuery({
    queryKey: queryKeys.floor,
    queryFn: async () => {
      const view = await fetchFloor();
      // Every floor response carries the server clock, so the offset is re-anchored on each
      // poll and the counters can never drift onto this machine's clock.
      syncTo(view.serverNow);
      return view;
    },
    refetchInterval: POLL_MS,
    // Keeps polling even when the tab is behind another window. React Query pauses interval
    // refetches for a hidden document by default, which would leave the floor showing a
    // frozen room and stale money the moment someone alt-tabs — the counter screen has to be
    // right whenever it is glanced at, not only when it has focus.
    refetchIntervalInBackground: true,
    staleTime: 0,
  });

  const unsettled = useQuery({
    queryKey: queryKeys.unsettledBills,
    queryFn: fetchUnsettledBills,
    refetchInterval: POLL_MS,
    refetchIntervalInBackground: true,
  });

  if (floor.isPending) {
    return (
      <div className="flex justify-center py-16">
        <Spinner label="Loading the floor…" />
      </div>
    );
  }

  return (
    <>
      {/* A failed poll is not a reason to blank the room: the last good view stays on screen
          with the problem stated above it. */}
      {floor.isError ? (
        <div className="mb-6">
          <Banner tone="danger">{messageOf(floor.error)}</Banner>
        </div>
      ) : null}

      {unsettled.data ? <UnsettledStrip bills={unsettled.data} /> : null}

      {/* The floor owns the screen. This is a single-purpose view on a machine that does
          nothing else, so the cards fill the viewport rather than sitting in one thin band
          across the top. Three columns on the counter's monitor gives a card near the 2:1 of
          a real table, and rows stretch to fill the height but stop at 23rem — past that the
          card is mostly empty gradient, which reads as something that failed to load rather
          than as breathing room. The cap is also headroom: more tables add rows at the same
          size instead of shrinking every card. */}
      <div className="grid min-h-[calc(100dvh-6.5rem)] auto-rows-[minmax(16rem,23rem)] grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {floor.data?.tables.map((table) => (
          <TableCard
            key={table.id}
            table={table}
            onStart={setStarting}
            onOpen={(occupied) => navigate(`/sessions/${occupied.session?.sessionId}`)}
          />
        ))}
      </div>

      {starting ? (
        <StartSessionModal
          table={starting}
          onClose={() => setStarting(null)}
          onStarted={(sessionId) => {
            setStarting(null);
            // Same route as before; the flag rides in history state so the session screen knows
            // this table was opened a moment ago rather than merely revisited. A refresh drops
            // it, which is right — the break belongs to the act of starting, not to the page.
            navigate(`/sessions/${sessionId}`, { state: { justStarted: true } });
          }}
        />
      ) : null}
    </>
  );
}
