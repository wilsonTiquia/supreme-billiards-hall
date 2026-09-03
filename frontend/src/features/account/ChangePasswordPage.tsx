import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/auth/useAuth';
import { useScreenTheme } from '@/app/useTheme';
import { queryKeys } from '@/api/queryKeys';
import { Card } from '@/components/Card';
import { Banner } from '@/components/Banner';
import { ChangePasswordForm } from './ChangePasswordForm';

/**
 * Changing your own password when you choose to, not because you are forced. Reachable from the
 * sidebar. Same form as the forced screen; only the frame and the after-state differ — here you
 * stay put and are told it worked, rather than being sent into the app.
 */
export function ChangePasswordPage() {
  const { user } = useAuth();
  // Follows the role's theme, like the screens on either side of it.
  useScreenTheme(user?.role === 'ADMIN' ? 'admin' : 'pos');
  const queryClient = useQueryClient();
  const [done, setDone] = useState(false);

  function handleSuccess() {
    setDone(true);
    // Changing a password ends the other sessions and clears any flag; re-read who we are.
    void queryClient.invalidateQueries({ queryKey: queryKeys.me });
  }

  return (
    <div className="mx-auto max-w-lg">
      <h1 className="text-heading text-text">Change password</h1>
      <p className="mt-1 text-body text-text-dim">
        Changing your password signs out your other devices. You stay signed in here.
      </p>

      <Card className="mt-6">
        {done ? (
          <Banner tone="info">Your password has been changed.</Banner>
        ) : null}
        <div className={done ? 'mt-6' : ''}>
          <ChangePasswordForm onSuccess={handleSuccess} />
        </div>
      </Card>
    </div>
  );
}
