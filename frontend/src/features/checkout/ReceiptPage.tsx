import { useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { uploadPaymentPhoto } from '@/api/endpoints/bills';
import type { Payment } from '@/api/types';
import { messageOf } from '@/api/errors';
import { useScreenTheme } from '@/app/useTheme';
import { Card } from '@/components/Card';
import { useAuth } from '@/auth/useAuth';
import { useBillMorph, type CardRect } from './useBillMorph';
import { ButtonLink } from '@/components/Button';
import { Icon } from '@/components/Icon';
import { ReceiptView } from './ReceiptView';

interface ReceiptOrigin { label: string; to: string }
const FLOOR: ReceiptOrigin = { label: 'Back to the floor', to: '/floor' };

export function ReceiptPage() {
  useScreenTheme('pos');
  const { billId = '' } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const state = location.state as
    | { origin?: ReceiptOrigin; justPaid?: Payment; photoNote?: string | null; from?: CardRect | null }
    | null;
  const origin: ReceiptOrigin = state?.origin ?? FLOOR;

  /*
   * Set when checkout sent us here, which is the overwhelmingly common way to arrive.
   *
   * Read once at mount so a re-render cannot resurrect it, and used for the two things the
   * stored receipt payload cannot tell us: whether this payment was a replay of one another
   * till had already taken, and whether a digital payment still wants its confirmation photo.
   */
  const [justPaid] = useState<Payment | null>(() => state?.justPaid ?? null);
  // Where the bill card was standing. Read once: the morph belongs to arriving from a payment,
  // and must not replay because something else re-rendered.
  const [from] = useState<CardRect | null>(() => state?.from ?? null);
  // The flag rides on the session, so the counter reads it without an ADMIN-only request.
  const { user } = useAuth();

  // A callback ref, so the morph starts the instant the card mounts — which is after the
  // receipt has loaded, not on the page's first render.
  const [card, setCard] = useState<HTMLDivElement | null>(null);
  useBillMorph(
    card,
    from,
    // A replayed payment gets no morph: nothing became anything, another till took the money.
    user?.checkoutAnimation === true && justPaid !== null && !justPaid.replayed,
  );
  const [photoNote, setPhotoNote] = useState<string | null>(() => state?.photoNote ?? null);

  const attachPhoto = useMutation({
    mutationFn: (file: File) => uploadPaymentPhoto((justPaid as Payment).id, file),
    onSuccess: () => {
      setPhotoNote('Photo attached.');
      navigate(location.pathname, { replace: true, state: { ...state, photoNote: 'Photo attached.' } });
    },
    onError: (caught) => setPhotoNote(messageOf(caught)),
  });

  return (
    <ReceiptView billId={billId} cardRef={setCard}
      back={<ButtonLink to={origin.to} variant="secondary"><Icon name="back" />{origin.label}</ButtonLink>}>
        {/* Only meaningful on the way in from checkout, and only for a payment that was
            already taken elsewhere. Silence on a normal payment is correct. */}
        {justPaid?.replayed ? (
          <p className="mt-4 text-label text-amount print:hidden">
            Already recorded — another till took this payment first. The money is collected.
          </p>
        ) : null}
        {justPaid?.duplicateReferenceOverridden ? (
          <p className="mt-2 text-label text-amount print:hidden">
            Recorded against a reference already used, on your say-so.
          </p>
        ) : null}

      {/* A missed or failed upload can be completed here without taking payment again. */}
      {justPaid && justPaid.method !== 'CASH' ? (
        <Card className="mt-4 print:hidden">
          <label className="text-label uppercase text-text-dim" htmlFor="payment-photo">
            Add photo (optional confirmation)
          </label>
          <input
            id="payment-photo"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            disabled={attachPhoto.isPending || photoNote === 'Photo attached.'}
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) attachPhoto.mutate(file);
              event.currentTarget.value = '';
            }}
            className="mt-2 block w-full text-body text-text-dim"
          />
          {photoNote ? <p role="status" className="mt-2 text-label text-text-dim">{photoNote}</p> : null}
        </Card>
      ) : null}

    </ReceiptView>
  );
}
