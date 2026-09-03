import { Button } from './Button';
import { Wordmark } from './Wordmark';
import { messageOf } from '@/api/errors';

/**
 * Shown when the backend cannot be reached at all. This is deliberately not the login form:
 * an operator who is told to sign in, when signing in is impossible, will spend the next
 * five minutes retyping a password that was never the problem.
 */
export function OfflineScreen({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <div className="flex min-h-dvh items-center justify-center bg-bg p-6">
      <div className="w-full max-w-md">
        <div className="mb-10 flex justify-center">
          <Wordmark size="lg" />
        </div>

        <div className="rounded-xl border border-danger/50 bg-surface p-6">
          <h1 className="text-heading text-text">Cannot reach the server</h1>
          <p className="mt-3 text-body text-text-dim">
            The POS backend is not responding. Sessions already running are unaffected — the
            server keeps the time and the totals, so nothing is lost while this screen is up.
          </p>
          <p className="mt-3 text-body text-text-dim">
            Keep writing chits until it returns, then retry.
          </p>
          <p className="mt-4 text-label text-text-dim">{messageOf(error)}</p>

          <Button className="mt-6 w-full" onClick={onRetry}>
            Try again
          </Button>
        </div>
      </div>
    </div>
  );
}
