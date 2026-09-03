import { useEffect, useRef } from 'react';
import { useClock } from './useClock';
import type { TableSessionSummary } from '@/api/types';

/**
 * The billable clock on screen.
 *
 * The server owns the figure and hands over two of them: `billedMinutes`, floored, which is
 * what the money is computed from, and `billedSeconds`, exact, which is what this animates.
 *
 * Anchoring on the exact figure is the whole point. Anchoring on the floored minute puts the
 * display up to 59 seconds behind real elapsed, so a refresh drops the counter backwards —
 * and then, once the server's minute ticks over while the display is still short, it jumps
 * forwards to catch up. Wrong in both directions, twice per minute. With an exact anchor
 * there is nothing left to be wrong about: re-anchoring on every poll moves the counter by
 * no more than network jitter.
 *
 * A paused session freezes at the server's figure — the charge must not advance while the
 * table is not being played.
 */
interface Anchor {
  baseMs: number;
  anchorAt: number;
  frozen: boolean;
}

// Below this, a correction would be indistinguishable from the round trip that delivered it,
// and re-anchoring would only make the seconds twitch.
const JITTER_TOLERANCE_MS = 2000;

export function useElapsed(session: TableSessionSummary | null): number {
  const { nowOnServer } = useClock();
  const anchor = useRef<Anchor | null>(null);

  const sessionId = session?.sessionId ?? null;
  const billedSeconds = session?.billedSeconds ?? 0;
  const status = session?.status ?? null;

  useEffect(() => {
    if (sessionId === null || status === null) {
      anchor.current = null;
      return;
    }

    const serverBaseMs = billedSeconds * 1000;
    const frozen = status === 'PAUSED';
    const current = anchor.current;

    // First sight of this session, or it moved into or out of a pause: take the server's
    // figure as it stands. Nothing about the timer survives a refresh, which is the point.
    if (!current || frozen || current.frozen) {
      anchor.current = { baseMs: serverBaseMs, anchorAt: nowOnServer(), frozen };
      return;
    }

    const showingMs = current.baseMs + (nowOnServer() - current.anchorAt);
    if (Math.abs(showingMs - serverBaseMs) > JITTER_TOLERANCE_MS) {
      anchor.current = { baseMs: serverBaseMs, anchorAt: nowOnServer(), frozen };
    }
  }, [sessionId, billedSeconds, status, nowOnServer]);

  if (!session) return 0;

  const current = anchor.current;
  if (!current) return billedSeconds * 1000;
  return current.frozen ? current.baseMs : current.baseMs + (nowOnServer() - current.anchorAt);
}
