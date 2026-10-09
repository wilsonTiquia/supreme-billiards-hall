// The same signal the compose healthcheck and the Deploy workflow use: /api/v1/time answers 401
// to an anonymous caller, and a 401 means Spring Security is up and answering.
export async function appAnswers(appUrl: string): Promise<boolean> {
    try {
        const res = await fetch(`${appUrl}/api/v1/time`, { signal: AbortSignal.timeout(10_000) });
        console.log(`app: ${appUrl}/api/v1/time answered ${res.status}`);
        return res.status === 200 || res.status === 401;
    } catch (err) {
        // Refused or timed out is the answer to the question, not a failure to ask it.
        console.log(`app: ${appUrl}/api/v1/time did not answer (${errorText(err)})`);
        return false;
    }
}

// fetch's own message is just "fetch failed"; the reason (ECONNREFUSED, ENOTFOUND) is on the cause.
function errorText(err: unknown): string {
    if (err instanceof Error && err.cause instanceof Error) {
        const cause = err.cause as NodeJS.ErrnoException;
        return `${err.message}: ${cause.code ?? cause.message}`;
    }
    return String(err);
}
