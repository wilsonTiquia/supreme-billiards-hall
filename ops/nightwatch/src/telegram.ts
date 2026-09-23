// One Bot API call. The token is part of the URL, so it is never put into an error message.
export async function sendTelegram(token: string, chatId: string, text: string): Promise<void> {
    const res = await fetch(`https://api.telegram.org/bot${token}/sendMessage`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ chat_id: chatId, text }),
        signal: AbortSignal.timeout(15_000),
    });
    if (!res.ok) {
        throw new Error(`Telegram refused the message: HTTP ${res.status} ${await res.text()}`);
    }
}
