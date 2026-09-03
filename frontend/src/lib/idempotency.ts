/**
 * One key per checkout attempt, reused on every retry of that attempt — including the
 * "Record anyway" resend after a duplicate reference, where reuse is what keeps the button
 * safe against a double-click. A new key is generated only when the operator starts a
 * genuinely new attempt. See API-CONTRACT §8.
 */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
