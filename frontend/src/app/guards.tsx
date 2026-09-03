import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '@/auth/useAuth';
import { Spinner } from '@/components/Spinner';
import { OfflineScreen } from '@/components/OfflineScreen';

function FullPageSpinner({ label }: { label: string }) {
  return (
    <div className="flex min-h-dvh items-center justify-center bg-bg">
      <Spinner label={label} />
    </div>
  );
}

/** Anything behind this needs a live session. Where the user was headed is preserved. */
export function RequireAuth() {
  const { status, error, retry } = useAuth();
  const location = useLocation();

  if (status === 'loading') return <FullPageSpinner label="Checking your session…" />;
  // An unreachable backend is not a signed-out user, and must not be shown as one.
  if (status === 'offline') return <OfflineScreen error={error} onRetry={retry} />;
  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <Outlet />;
}

/**
 * Admin-only routes. This is a convenience for layout and navigation, never the security
 * boundary — the server enforces the role and omits cost fields regardless of what the
 * client believes.
 */
export function RequireAdmin() {
  const { user } = useAuth();
  if (user?.role !== 'ADMIN') return <Navigate to="/floor" replace />;
  return <Outlet />;
}

/** Sends a freshly logged-in user to the screen their role actually uses. */
export function HomeRedirect() {
  const { user } = useAuth();
  return <Navigate to={user?.role === 'ADMIN' ? '/dashboard' : '/floor'} replace />;
}
