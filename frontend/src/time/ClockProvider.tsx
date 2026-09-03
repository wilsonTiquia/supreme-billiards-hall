import { createContext, useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchServerTime } from '@/api/endpoints/time';
import { queryKeys } from '@/api/queryKeys';
import { useAuth } from '@/auth/useAuth';
import type { IsoInstant } from '@/api/types';

/**
 * The server is the only clock. This holds the single offset between the server and this
 * machine, computed from a server timestamp and used to render every counter locally.
 *
 * The browser's own clock is never trusted for anything billable — a counter drawn from
 * this offset is a display convenience so the screen does not freeze between polls, and it
 * is always reconciled to the server's own billedMinutes and timeAmount.
 */
export interface ClockContextValue {
  /** serverNow - clientNow, in milliseconds. Zero until the first sync. */
  offsetMs: number;
  synced: boolean;
  /** The current time as the server would report it. */
  nowOnServer: () => number;
  /** Re-anchor from any response carrying serverNow — the floor poll does this in Phase B. */
  syncTo: (serverNow: IsoInstant) => void;
}

export const ClockContext = createContext<ClockContextValue | null>(null);

export function ClockProvider({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const [offsetMs, setOffsetMs] = useState(0);
  const [synced, setSynced] = useState(false);

  // Held in a ref as well so nowOnServer() stays referentially stable for the ticking
  // counters in Phase B, which must not re-subscribe on every offset correction.
  const offsetRef = useRef(0);

  const syncTo = useCallback((serverNow: IsoInstant) => {
    const next = Date.parse(serverNow) - Date.now();
    offsetRef.current = next;
    setOffsetMs(next);
    setSynced(true);
  }, []);

  // /api/v1/time requires authentication, so this waits for a session rather than firing a
  // guaranteed 401 during login.
  useQuery({
    queryKey: queryKeys.time,
    queryFn: async () => {
      const time = await fetchServerTime();
      syncTo(time.serverNow);
      return time;
    },
    enabled: status === 'authenticated',
    staleTime: 5 * 60_000,
    refetchInterval: 5 * 60_000,
  });

  const nowOnServer = useCallback(() => Date.now() + offsetRef.current, []);

  const value = useMemo<ClockContextValue>(
    () => ({ offsetMs, synced, nowOnServer, syncTo }),
    [offsetMs, synced, nowOnServer, syncTo],
  );

  return <ClockContext.Provider value={value}>{children}</ClockContext.Provider>;
}
