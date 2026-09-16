import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/auth/useAuth';
import { queryKeys } from '@/api/queryKeys';
import { Button } from '@/components/Button';
import { Card } from '@/components/Card';
import { Banner } from '@/components/Banner';
import { ChangePasswordForm } from './ChangePasswordForm';

/** Shared account screen. The legacy /account/password URL also renders this page.
 * Reuses the forced-password form without changing its validation or session behavior.
 */
export function ChangePasswordPage() {
  const { user, logout } = useAuth();
  const [signingOut, setSigningOut] = useState(false);
  const queryClient = useQueryClient();
  const [done, setDone] = useState(false);

  function handleSuccess() {
    setDone(true);
    // Changing a password ends the other sessions and clears any flag; re-read who we are.
    void queryClient.invalidateQueries({ queryKey: queryKeys.me });
  }

  return (
    <div className="mx-auto max-w-lg">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-heading text-text">Your account</h1>
          <p className="mt-1 text-body text-text-dim">{user?.fullName}</p>
        </div>
        <Button variant="secondary" pending={signingOut} onClick={() => {
          setSigningOut(true);
          void logout().catch(() => { /* AuthProvider always clears the local session. */ });
        }}>Sign out</Button>
      </div>
      <h2 className="mt-8 text-heading text-text">Change password</h2>
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
