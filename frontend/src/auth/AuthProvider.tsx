import { createContext, useCallback, useEffect, useMemo, useRef, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { setUnauthorizedHandler } from '@/api/client';
import { fetchMe, login as loginRequest, logout as logoutRequest } from '@/api/endpoints/auth';
import { queryKeys } from '@/api/queryKeys';
import type { CurrentUser, LoginRequest } from '@/api/types';

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous' | 'offline';

export interface AuthContextValue {
  user: CurrentUser | null;
  status: AuthStatus;
  login: (credentials: LoginRequest) => Promise<CurrentUser>;
  logout: () => Promise<void>;
  loggingIn: boolean;
  /** Why the session could not be established, when status is 'offline'. */
  error: unknown;
  /** Re-probe the session. Used by the offline screen's retry. */
  retry: () => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  // Boot-time probe. A 401 resolves to null — not being logged in yet is the ordinary cold
  // start. Anything else throws, so an unreachable backend is reported as such instead of
  // masquerading as a signed-out user.
  const {
    data: user,
    isPending,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: queryKeys.me,
    queryFn: fetchMe,
    staleTime: Infinity,
    // No retries on the probe that gates the entire UI. If the backend cannot be reached,
    // say so at once and offer Try again — an operator watching a spinner through several
    // invisible retries has no idea whether to wait or to start writing chits. Background
    // polls retry; the thing standing between the staff and the screen does not.
    retry: false,
  });

  /* ── The global 401 handler ──────────────────────────────────────────────────────
     Registered here because it needs the router and the cache, and read by client.ts
     through a plain function slot so that file stays free of React. Any 401 from any
     endpoint lands here; login and the boot probe opt out.

     The guard flag coalesces a burst: clearing the cache makes every mounted query
     refetch, and several of them can 401 together before the redirect unmounts them.
     Without it the app would push a dozen identical navigations. */
  const redirecting = useRef(false);

  const handleUnauthorized = useCallback(() => {
    if (redirecting.current) return;
    redirecting.current = true;

    // clear(), not invalidate: cached bills and totals from the dead session must not be
    // on screen for even a frame if a different user logs in next.
    queryClient.clear();
    navigate('/login', { replace: true });

    window.setTimeout(() => {
      redirecting.current = false;
    }, 1000);
  }, [navigate, queryClient]);

  useEffect(() => {
    setUnauthorizedHandler(handleUnauthorized);
    return () => setUnauthorizedHandler(null);
  }, [handleUnauthorized]);

  const loginMutation = useMutation({
    mutationFn: loginRequest,
    onSuccess: (loggedIn) => {
      // Seeded directly so the guards see the new user without a second round trip.
      queryClient.setQueryData(queryKeys.me, loggedIn);
      redirecting.current = false;
    },
  });

  const login = useCallback(
    (credentials: LoginRequest) => loginMutation.mutateAsync(credentials),
    [loginMutation],
  );

  const logout = useCallback(async () => {
    try {
      await logoutRequest();
    } finally {
      // Even if the call fails the local session is over; never strand the operator on a
      // screen they believe they have left.
      queryClient.clear();
      queryClient.setQueryData(queryKeys.me, null);
      navigate('/login', { replace: true });
    }
  }, [navigate, queryClient]);

  const retry = useCallback(() => {
    void refetch();
  }, [refetch]);

  const status: AuthStatus = isPending
    ? 'loading'
    : isError
      ? 'offline'
      : user
        ? 'authenticated'
        : 'anonymous';

  const value = useMemo<AuthContextValue>(
    () => ({
      user: user ?? null,
      status,
      login,
      logout,
      loggingIn: loginMutation.isPending,
      error,
      retry,
    }),
    [user, status, login, logout, loginMutation.isPending, error, retry],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
