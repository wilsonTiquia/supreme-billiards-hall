/**
 * The one error type the whole app throws and catches. Every failure — a coded 409, a plain
 * 409, a 403, a dead backend — arrives here, so no component ever has to know whether it is
 * holding a Response, a TypeError or something else.
 */

/** Machine-readable codes the server sets on the envelope. See API-CONTRACT §1. */
export const ErrorCode = {
  StaleBillVersion: 'STALE_BILL_VERSION',
  DuplicatePaymentReference: 'DUPLICATE_PAYMENT_REFERENCE',
} as const;

export type ErrorCodeValue = (typeof ErrorCode)[keyof typeof ErrorCode];

/** Status used when the request never reached the server at all. */
export const NETWORK_ERROR = 0;

export class ApiError extends Error {
  readonly status: number;
  /**
   * Deliberately `string`, not a union of the two known codes: the backend may add a code
   * later, and a closed union would make an unrecognised one impossible to carry or log.
   * Narrow with `hasCode()` at the point of use instead.
   */
  readonly code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }

  /** True when the request never reached the server — backend down, network gone. */
  get isOffline(): boolean {
    return this.status === NETWORK_ERROR;
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

export function hasCode(error: unknown, code: ErrorCodeValue): boolean {
  return isApiError(error) && error.code === code;
}

/**
 * Anything shown to a user goes through here. Server messages are written for a human and
 * are always preferred; the fallback exists only for a thrown value that is not an ApiError.
 */
export function messageOf(error: unknown): string {
  if (isApiError(error)) return error.message;
  if (error instanceof Error && error.message) return error.message;
  return 'Something went wrong.';
}
