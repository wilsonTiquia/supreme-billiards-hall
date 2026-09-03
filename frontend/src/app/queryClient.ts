import { QueryClient } from '@tanstack/react-query';
import { isApiError } from '@/api/errors';

/**
 * Retry policy, stated once.
 *
 * A 4xx is an answer, not a hiccup — retrying a 403 or a 409 just delays the message the
 * operator needs to read. Only a request that never reached the server is worth repeating,
 * and then only briefly, so a backend restart heals itself without a page reload.
 *
 * Mutations never retry automatically. Everything that mutates here moves money or stock,
 * and the safe retry for a payment is a human pressing the button again with the same
 * idempotency key — not a timer doing it unobserved.
 *
 * networkMode 'always' is deliberate. The default ('online') parks a request as "paused"
 * whenever the browser's own connectivity heuristic says offline, and a paused query looks
 * exactly like a hung one: a spinner that never resolves and never raises an error the
 * screen can show. The backend here is on the same LAN as the counter, so the browser's
 * view of "the internet" says nothing useful about whether it is reachable. Always attempt,
 * and let a real failure become a real error.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      networkMode: 'always',
      retry: (failureCount, error) => {
        if (isApiError(error) && !error.isOffline) return false;
        return failureCount < 2;
      },
      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 5000),
      staleTime: 10_000,
      refetchOnWindowFocus: true,
    },
    mutations: {
      networkMode: 'always',
      retry: false,
    },
  },
});
