import { uploadPaymentPhoto } from '@/api/endpoints/bills';
import { messageOf } from '@/api/errors';
import type { Payment } from '@/api/types';

/** Payment has already succeeded. A photo failure must never retry the charge. */
export async function attachSelectedPhoto(payment: Payment, photo: File | null): Promise<string | null> {
  if (!photo || payment.method === 'CASH') return null;
  try {
    await uploadPaymentPhoto(payment.id, photo);
    return 'Photo attached.';
  } catch (caught) {
    return `Payment recorded. Photo could not be attached: ${messageOf(caught)} Use Add photo on the receipt to try again.`;
  }
}
