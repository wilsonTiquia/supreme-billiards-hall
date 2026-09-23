// Nightwatch: runs once at 06:00 Manila (a systemd timer on the host), asks the database whether
// last night was closed, tells the owner on Telegram if it was not, and exits.
//
// It lives outside the Spring app on purpose. A check inside the app cannot report that the app
// is down, and a dead app at 02:00 means both an unclosed night and a silent alarm.
//
// Exit codes: 0 = checked (and sent whatever needed sending), 1 = could not check,
// 2 = refused to check (wrong hour, bad argument). Never 0 for "could not check".
//
// Every date and the Manila clock come from PostgreSQL (business_date_of, V1). Nothing here
// computes a business date or reads the local clock.

import pg from "pg";
import { appAnswers } from "./app-alive.ts";
import { nightClosed } from "./night-closed.ts";
import { sendTelegram } from "./telegram.ts";

const REFUSED = 2;

class ConfigError extends Error {}
class TelegramFailure extends Error {}

function required(name: string): string {
    const value = process.env[name] ?? "";
    if (value === "") throw new ConfigError(`${name} is not set`);
    return value;
}

async function main(args: string[]): Promise<number> {
    if (args.length > 1 || (args.length === 1 && !/^\d{4}-\d{2}-\d{2}$/.test(args[0]))) {
        console.error("usage: nightwatch [YYYY-MM-DD]   (the business date to check; default: the night that just ended)");
        return REFUSED;
    }
    const explicitDate: string | undefined = args[0];

    const token = process.env.TELEGRAM_BOT_TOKEN ?? "";
    const chatId = process.env.TELEGRAM_CHAT_ID ?? "";
    if (token !== "" && chatId === "") throw new ConfigError("TELEGRAM_BOT_TOKEN is set but TELEGRAM_CHAT_ID is not");

    // An empty token is "disabled", so a laptop or CI run never texts anyone.
    const notify = async (text: string): Promise<void> => {
        if (token === "") {
            console.log(`telegram disabled (TELEGRAM_BOT_TOKEN empty), would send: ${text}`);
            return;
        }
        await sendTelegram(token, chatId, text);
        console.log(`telegram sent: ${text}`);
    };

    try {
        return await check(explicitDate, notify);
    } catch (err) {
        // Unattended (the timer, no date given): the owner hears that the alarm itself failed,
        // because a journal entry nobody reads is the same silence as a quiet night. By hand,
        // the person at the keyboard sees the error and nobody gets texted about a typo.
        if (explicitDate === undefined && !(err instanceof TelegramFailure) && !(err instanceof ConfigError)) {
            try {
                await notify("Supreme Billiards: the 06:00 night check could not run. Check the server.");
            } catch (sendErr) {
                console.error("nightwatch: could not report the failure on Telegram either:", sendErr);
            }
        }
        throw err;
    }
}

async function check(explicitDate: string | undefined, notify: (text: string) => Promise<void>): Promise<number> {
    const dbUrl = new URL(required("NIGHTWATCH_DB_URL"));
    dbUrl.password = encodeURIComponent(required("NIGHTWATCH_DB_PASSWORD"));
    const appUrl = required("NIGHTWATCH_APP_URL");

    const db = new pg.Client({
        connectionString: dbUrl.toString(),
        application_name: "nightwatch",
        connectionTimeoutMillis: 10_000,
        statement_timeout: 10_000,
    });
    await db.connect();
    try {
        const clock = (await db.query<{ default_date: string; in_window: boolean; manila_time: string }>(
            `SELECT business_date_of(now())::text AS default_date,
                    (now() AT TIME ZONE 'Asia/Manila')::time >= '05:00'
                      AND (now() AT TIME ZONE 'Asia/Manila')::time < '10:00' AS in_window,
                    to_char(now() AT TIME ZONE 'Asia/Manila', 'HH24:MI') AS manila_time`)).rows[0];

        // Before 05:00 the night is still running; from 10:00 business_date_of(now()) is TONIGHT,
        // which is open by definition. Either way "not closed" would be a false alarm, and false
        // alarms teach the owner to ignore his phone.
        if (explicitDate === undefined && !clock.in_window) {
            console.error(`nightwatch: refused. It is ${clock.manila_time} in Manila; without a date the night to check is `
                + `business_date_of(now()) = ${clock.default_date}, which only means "last night" between 05:00 and 10:00. `
                + `Run it in that window, or name the night: nightwatch YYYY-MM-DD`);
            return REFUSED;
        }

        const target = (await db.query<{ business_date: string; label: string }>(
            `SELECT d::text AS business_date, to_char(d, 'FMDay FMDD FMMonth') AS label
               FROM (SELECT $1::date AS d) t`,
            [explicitDate ?? clock.default_date])).rows[0];
        console.log(`checking the night of ${target.label} (business_date ${target.business_date}), Manila time ${clock.manila_time}`);

        const send = async (text: string) => {
            try {
                await notify(text);
            } catch (err) {
                throw new TelegramFailure(err instanceof Error ? err.message : String(err), { cause: err });
            }
        };

        const branches = (await db.query<{ id: string; name: string }>(
            "SELECT id, name FROM branch WHERE is_active ORDER BY code")).rows;
        if (branches.length === 0) throw new Error("no active branch in the branch table — nothing to check");

        // The night first, then the app, so a dead app is reported with what the database knows:
        // "may not have been closed" about a night that closed at 02:15 is a false worry, and a
        // false worry at 06:00 costs the same trust as a missed alarm.
        const nights = [];
        for (const branch of branches) {
            nights.push({ branch, night: await nightClosed(db, branch.id, target.business_date) });
        }
        const appUp = await appAnswers(appUrl);

        for (const { branch, night } of nights) {
            const who = branches.length > 1 ? `Supreme Billiards (${branch.name})` : "Supreme Billiards";
            if (!appUp) {
                // A dead app replaces "not closed": the likely reason is that nothing was running.
                await send(`${who}: the POS did not answer at ${clock.manila_time}. ` + (night.state === "closed"
                    ? `The night of ${target.label} was closed normally at ${night.closedAt}.`
                    : `The night of ${target.label} may not have been closed.`));
                continue;
            }
            switch (night.state) {
                case "closed":
                    console.log(`${branch.name}: closed at ${night.closedAt} Manila, nothing to send`);
                    break;
                case "counted-not-closed":
                    await send(`${who}: the night of ${target.label} was counted but never closed.`);
                    break;
                case "uncounted":
                    await send(`${who}: the night of ${target.label} was never closed. Nobody counted the drawer.`);
                    break;
            }
        }
        return 0;
    } finally {
        await db.end();
    }
}

main(process.argv.slice(2)).then(
    (code) => { process.exitCode = code; },
    (err) => {
        console.error("nightwatch: could not check:", err instanceof ConfigError ? err.message : err);
        process.exitCode = 1;
    });
