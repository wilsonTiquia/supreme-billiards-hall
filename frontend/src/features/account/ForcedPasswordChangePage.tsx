import { Navigate, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/auth/useAuth';
import { useScreenTheme } from '@/app/useTheme';
import { queryKeys } from '@/api/queryKeys';
import { Wordmark } from '@/components/Wordmark';
import { Card } from '@/components/Card';
import { ChangePasswordForm } from './ChangePasswordForm';

/**
 * The wall a flagged user hits after signing in. There is no shell around it — no nav, no back
 * door — because the account is not usable for anything else until the password is replaced. A
 * default was set at install (or an admin handed over a temporary), and it exists only to be
 * changed.
 */
export function ForcedPasswordChangePage() {
  useScreenTheme('pos');
  const { user } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const home = user?.role === 'ADMIN' ? '/dashboard' : '/floor';

  // Only flagged users belong here. Anyone else (including this same user the instant after a
  // successful change) is sent to where a normal login would land.
  if (!user?.mustChangePassword) return <Navigate to={home} replace />;

  async function handleSuccess() {
    // Re-read who we are so the cleared flag is in the cache before we route into the app,
    // otherwise the gate below would bounce us straight back here.
    await queryClient.invalidateQueries({ queryKey: queryKeys.me });
    navigate(home, { replace: true });
  }

  return (
    <div className="flex min-h-dvh items-center justify-center bg-bg p-6">
      <div className="w-full max-w-md">
        <div className="mb-10 flex justify-center">
          <Wordmark size="lg" />
        </div>

        <Card>
          <h1 className="text-heading text-text">Set a new password</h1>
          <p className="mt-2 text-body text-text-dim">
            This account was set up with a temporary password. Choose your own before using the
            till — the temporary one cannot be used for anything else.
          </p>

          <div className="mt-6">
            <ChangePasswordForm onSuccess={handleSuccess} submitLabel="Set password and continue" />
          </div>
        </Card>
      </div>
    </div>
  );
}
