import { useState, type FormEvent } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '@/auth/useAuth';
import { Button } from '@/components/Button';
import { useScreenTheme } from '@/app/useTheme';
import { Field } from '@/components/Field';
import { Banner } from '@/components/Banner';
import { Wordmark } from '@/components/Wordmark';
import { OfflineScreen } from '@/components/OfflineScreen';
import { messageOf } from '@/api/errors';

export function LoginPage() {
  // The door to the counter is a counter screen: without this it keeps whatever theme the
  // last person left, so signing on for the night starts with a white flash.
  useScreenTheme('pos');
  const { status, user, login, loggingIn, error: sessionError, retry } = useAuth();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Reached /login directly while the backend is down: say so rather than presenting a
  // form that cannot succeed.
  if (status === 'offline') return <OfflineScreen error={sessionError} onRetry={retry} />;

  if (status === 'authenticated' && user) {
    // A user who must change their password goes straight to the wall — not to where they were
    // headed, which they cannot use yet anyway.
    if (user.mustChangePassword) return <Navigate to="/change-password" replace />;
    const from = (location.state as { from?: string } | null)?.from;
    const home = user.role === 'ADMIN' ? '/dashboard' : '/floor';
    return <Navigate to={from && from !== '/login' ? from : home} replace />;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      await login({ username, password });
    } catch (caught) {
      // A 401 here is a bad password, not a dead session — it stays on this screen and says
      // what the server said.
      setError(messageOf(caught));
    }
  }

  return (
    <div className="flex min-h-dvh items-center justify-center bg-bg p-6">
      <div className="w-full max-w-md">
        <div className="mb-10 flex justify-start">
          <Wordmark size="lg" />
        </div>

        <form onSubmit={handleSubmit} className="flex flex-col gap-6" noValidate>
          <Field
            label="Username"
            name="username"
            value={username}
            autoFocus
            autoComplete="username"
            onChange={(e) => setUsername(e.target.value)}
          />
          <Field
            label="Password"
            name="password"
            type={showPassword ? 'text' : 'password'}
            trailing={
              <button type="button" aria-label={showPassword ? 'Hide password' : 'Show password'}
                aria-pressed={showPassword} onClick={() => setShowPassword((shown) => !shown)}
                className="hit flex w-12 items-center justify-center rounded-lg text-text-dim hover:text-text">
                <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden>
                  <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
                  <circle cx="12" cy="12" r="3" />
                  {showPassword ? <path d="m3 3 18 18" /> : null}
                </svg>
              </button>
            }
            value={password}
            autoComplete="current-password"
            onChange={(e) => setPassword(e.target.value)}
          />

          {error ? <Banner tone="danger">{error}</Banner> : null}

          <Button
            type="submit"
            className="h-14 text-heading"
            pending={loggingIn}
            disabled={!username || !password}
          >
            {loggingIn ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>
      </div>
    </div>
  );
}
