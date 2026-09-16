# Deploying to the VPS — Debian 12, Docker Compose, Caddy

This is the "hosting-day" section of `docs/GO-LIVE.md` made concrete. It is written for the
person doing it on the box, in the order it is done. Every command is meant to be pasted.

The box: OVH VPS, Debian 12, `51.79.241.146`. The name: `billiards.frasi.tech`, an A record on
Cloudflare in **DNS-only** mode (grey cloud, not proxied). Keep it DNS-only — Caddy answers the
Let's Encrypt challenge itself, and the app needs Caddy to see the real client address rather
than Cloudflare's. Both break the day someone clicks the orange cloud.

What runs on the box, all from `compose.yaml`:

| Container | Image | Reachable from |
|---|---|---|
| `db` | `postgres:18` | the other two containers only |
| `app` | built from `Dockerfile` (Java 21 JRE, the fat jar, non-root) | `caddy` only |
| `caddy` | `caddy:2` | the internet, on 80 and 443 |

Neither `db` nor `app` has a `ports:` entry. That is not a firewall rule; it is the absence of
a hole. It is also the only thing that works here: **Docker's published ports bypass `ufw`**
(Docker writes its own iptables chain ahead of ufw's), so a `ports: "5432:5432"` added to `db`
would be open to the internet no matter what `ufw status` says. GO-LIVE item 13 ("the database
is never reachable from outside") and requirement 2 of `application-prod.properties` ("the app
is not reachable except through the proxy") are both satisfied by construction. Do not add
`ports:` to either service, ever, "just to look at something" — use `docker compose exec`.

---

## 0. Before starting

- [ ] You can `ssh` to the box as `root` or the OVH-provided `debian` user, with a key
- [ ] The Cloudflare A record `billiards.frasi.tech → 51.79.241.146` exists and is grey-cloud
      (`dig +short billiards.frasi.tech` from anywhere returns `51.79.241.146`, not a Cloudflare
      address)
- [ ] You have chosen a database password and a first owner password. Write both in the same
      place the owner keeps the other passwords, not in a chat

---

## 1. Debian 12 prep

As `root` (or `sudo -i` from the `debian` user). One block at a time; read what each does.

```bash
# Everything current before anything is installed.
apt update && apt upgrade -y

# The hall's clock. The database and the 05:00 auto-close carry their own Asia/Manila and do
# not care what the host says; this is so cron lines and `docker compose logs` read in hall time.
timedatectl set-timezone Asia/Manila

# The deploy user. Not root, in the docker group so `docker compose` works without sudo.
adduser --gecos "" pos
usermod -aG sudo pos
mkdir -p /home/pos/.ssh
cp /root/.ssh/authorized_keys /home/pos/.ssh/authorized_keys   # or ~debian/.ssh/authorized_keys
chown -R pos:pos /home/pos/.ssh
chmod 700 /home/pos/.ssh && chmod 600 /home/pos/.ssh/authorized_keys
```

Now — **in a second terminal, before touching sshd** — confirm `ssh pos@51.79.241.146`
works with the key. Only then:

```bash
# Keys only, no root login. The box has a public address; password ssh is a lockout waiting
# for a botnet to find it.
sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/^#\?PermitRootLogin .*/PermitRootLogin no/' /etc/ssh/sshd_config
systemctl restart ssh
```

```bash
# Firewall: 22, 80, 443 and nothing else. Enable before Docker is installed so the order of
# the iptables chains is the one ufw expects.
apt install -y ufw
ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp        # HTTP/3
ufw --force enable
ufw status verbose       # expect: 22, 80, 443 ALLOW IN; everything else denied
```

```bash
# Security updates apply themselves. The reboot they occasionally need is left to a human —
# see "Updating" below for when.
apt install -y unattended-upgrades apt-listchanges
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
unattended-upgrade --dry-run --debug 2>&1 | tail -3   # should end without an error
```

```bash
# Docker Engine and the compose plugin, from Docker's own repository. Debian's packaged
# docker.io is older and ships without `docker compose`.
apt install -y ca-certificates curl git rclone
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
apt update
apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
usermod -aG docker pos
docker compose version      # expect: Docker Compose version v2 or later
```

```bash
# The image build runs Maven and Vite; on a 2 GB VPS that is OOM territory without swap.
# Skip if `free -g` shows 4 GB or more.
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

Log out. Everything from here is done as `pos`, and `ssh pos@51.79.241.146` must work.

---

## 2. First boot

Run this section as `pos`. This repository is private. Before cloning, create a repository-specific SSH deploy key on the VPS:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/supreme_deploy -C "pos@supreme-vps read-only deploy"
cat ~/.ssh/supreme_deploy.pub
```

Add **only the public key** to the repository's **Settings → Deploy keys**. Leave **Allow write access** unchecked. Keep the private key on the VPS. Configure SSH to use it:

```bash
cat >> ~/.ssh/config <<'EOF'
Host github.com
  User git
  IdentityFile ~/.ssh/supreme_deploy
  IdentitiesOnly yes
EOF
chmod 600 ~/.ssh/config
```

Verify GitHub's host-key fingerprint against its published SSH fingerprints before accepting a first connection. Use the SSH clone URL below; the HTTPS URL does not use this deploy key. A restore host needs its own read-only deploy key configured in the same way.

```bash
sudo mkdir -p /opt/supreme && sudo chown pos:pos /opt/supreme
git clone git@github.com:wilsonTiquia/supreme-billiards-hall.git /opt/supreme
cd /opt/supreme
cp .env.example .env
chmod 600 .env
```

Edit `.env` (`nano .env`). Every line gets a value:

```
SITE_ADDRESS=billiards.frasi.tech
CADDY_LOCAL_CERTS=
DB_PASSWORD=<the database password you chose>
SUPREME_BOOTSTRAP_ADMIN_PASSWORD=<the first owner password you chose>
RCLONE_REMOTE=
```

`CADDY_LOCAL_CERTS` stays empty on the VPS — it is the laptop-check switch (section 6).

`RCLONE_REMOTE` is filled in at step 6. Leave it empty for now.

**Why the bootstrap variable, and why it is easy here.** A fresh database is seeded by `V2` with
the `owner` and `counter` accounts and then has both disabled by `V9` — no password anyone
knows works. `AdminPasswordBootstrap` reads `SUPREME_BOOTSTRAP_ADMIN_PASSWORD` from the
environment on boot and, if the owner still carries the disabled sentinel, sets that as the
owner's password, once. On the Mac, GO-LIVE item 1 is the open question of how to get that
variable into a launchd-started process. On the VPS there is no question: compose passes it
from `.env` into the container and nothing else. That item is closed for the VPS.

```bash
# The app runs as uid 10001 inside the container and writes uploads to /data, which is this
# folder. Create it before the first `up` — if compose creates it, it is owned by root and
# the first product-image upload fails with a permission error in the log.
mkdir -p data backups
sudo chown 10001:10001 data

docker compose up -d --build
```

The first build downloads three base images and every Maven and npm dependency; on the VPS
expect five to ten minutes. Then watch the app come up:

```bash
docker compose logs -f app
```

Four lines to find, in this order. **All four, or stop.**

1. `The following 1 profile is active: "prod"` — near the top. If instead it says
   `No active profile set, falling back to 1 default profile: "default"`, nothing in
   `application-prod.properties` is on: the session cookie is not `Secure`, and the login
   lockout is keyed on Caddy's address instead of the client's. Check `SPRING_PROFILES_ACTIVE`
   in `compose.yaml` and do not go further.
2. `Successfully applied 20 migrations to schema "public", now at version v20` — Flyway ran
   V1 through V20 on the empty database. (A later clone may say a higher number; it must say
   *applied*, not *validated*, on a first boot.)
3. `Started BilliardsHallSystemApplication` — and a few seconds later `docker compose ps`
   shows `app` as `healthy`.
4. `Initialised the admin password for 'owner' from SUPREME_BOOTSTRAP_ADMIN_PASSWORD. Log in,
   change it immediately, then unset the variable.` — from `AdminPasswordBootstrap`, just
   after the started line.

Caddy takes a minute more the first time — it has to obtain the certificate. Two checks:

```bash
docker compose exec caddy env | grep SITE_ADDRESS   # must print billiards.frasi.tech
docker compose logs caddy | grep -E "obtained successfully|acme"
```

The first matters more than it looks: if `SITE_ADDRESS` did not reach the container, Caddy
serves plain HTTP on port 80 for any name and never asks for a certificate, with no error.
The second must show `certificate obtained successfully` with `"issuer":"acme"` — `"local"`
means `CADDY_LOCAL_CERTS` is set and the browser will warn. Then, in a browser:

- [ ] `https://billiards.frasi.tech` loads the login screen, padlock, no warning
- [ ] `http://billiards.frasi.tech` redirects to https
- [ ] Log in as `owner` with the bootstrap password
- [ ] Change the password (the menu under the owner's name → Change password). Write the new
      one where the owner keeps passwords.
- [ ] `nano .env` — **delete the `SUPREME_BOOTSTRAP_ADMIN_PASSWORD` line** (or empty it). The
      variable can never overwrite a password that has been set, so leaving it is not
      dangerous; it is untidy, and a value sitting in a file on a public box for no reason is
      the habit this project does not have.
- [ ] `docker compose up -d` — recreates `app` with the new environment (a few seconds' restart)
- [ ] `docker compose logs app | grep BOOTSTRAP` — **nothing**. If it prints `...already has a
      password; leaving it unchanged`, the line is still in `.env`.

---

## 3. The forged-header test

`application-prod.properties` turns on `server.forward-headers-strategy=framework`, which makes
the app believe `X-Forwarded-For`. The login lockout is keyed on that address. It is safe only
if the proxy **overwrites** the header with the connecting client's real address; a proxy that
*appends* to a client-supplied value hands every client a way to pick its own address, evade
the per-address lockout, or lock somebody else out. Caddy's documentation says it ignores
forwarded headers from untrusted clients. Do not take that from the documentation; check what
the app records.

From your own machine (not the VPS):

```bash
# One deliberately wrong login, carrying a forged address.
curl -s -o /dev/null -w '%{http_code}\n' https://billiards.frasi.tech/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -H 'X-Forwarded-For: 203.0.113.99' \
  -d '{"username":"owner","password":"definitely-wrong"}'
```

Expect `401`. On the VPS:

```bash
docker compose logs app | grep "Failed login"
```

Expect `Failed login for username 'owner' from <your public address>` — the address you are
sitting behind (`curl ifconfig.me` from the same machine tells you what it is). **If the line
says `203.0.113.99`, stop.** The proxy passed the forged value through and the lockout is
spoofable; `trusted_proxies` has been set somewhere it should not be, or something is in front
of Caddy.

Then the lockout itself, which proves the key is the real address and not the forged one:

```bash
# Five wrong passwords, each with a DIFFERENT forged address. If the forgery worked, each is
# a fresh address and none of them accumulates.
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w '%{http_code} ' https://billiards.frasi.tech/api/v1/auth/login \
    -H 'Content-Type: application/json' -H "X-Forwarded-For: 203.0.113.$i" \
    -d '{"username":"owner","password":"definitely-wrong"}'
done; echo
# The sixth, with the RIGHT password.
curl -s -w '\n%{http_code}\n' https://billiards.frasi.tech/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"owner","password":"<the real password>"}'
```

Expect `401 401 401 401 401` and then **`429`** with `Too many failed attempts` — five failures
from your real address locked `owner` at your address for fifteen minutes, forged addresses
notwithstanding. The lockout clears itself; wait fifteen minutes, or `docker compose restart app`.

**What was seen on the local run of this compose file (Docker Desktop on the Mac, 16 September
2026, `billiards.frasi.tech` resolved to 127.0.0.1, `CADDY_LOCAL_CERTS=local_certs`):**

- One wrong login with `X-Forwarded-For: 203.0.113.99` → `401`, and the app logged
  `Failed login for username 'owner' from 192.168.65.1`. That is Docker Desktop's VM gateway,
  the address every connection from the Mac arrives from — the client as Caddy sees it. The
  forged value appeared nowhere. On the Linux VPS the same line will show the caller's public
  address, because Docker's NAT preserves the source address on a published port.
- Five wrong logins with five different forged addresses → `401 401 401 401 429`; then the
  right password → `429 Too many failed attempts`. All five failures were recorded against
  `192.168.65.1`. The lockout is keyed on the real address; the forgeries changed nothing.
- That it was the forwarded header being honoured, and not the strategy silently off: the
  `caddy` container's own address was `172.18.0.4`, and the app never recorded that either.
  With `forward-headers-strategy` inactive it would have.
- The control, which is why requirement 2 exists: the same forged request sent to `app:8080`
  **directly from inside the compose network**, bypassing Caddy, was recorded as
  `from 203.0.113.77`. Anything that can reach the app's port chooses its own address. Nothing
  outside the compose network can, because `app` publishes no port.

---

## 4. The upload-path check

`supreme.product-image.path` is set by the environment variable `SUPREME_PRODUCTIMAGE_PATH`
(Spring's relaxed binding: dots become underscores, the dash disappears, upper case). If that
name is wrong, Spring falls back to the property's default, `${user.home}/SupremeData` — which
inside the container is `/app/SupremeData`, on the container's own filesystem, **not backed up
and gone on the next `up --build`**. There is no error; the upload just works, somewhere else.

- [ ] Log in as owner, Admin → Products, upload an image to any product
- [ ] On the VPS: `ls -la data/product-images/` — the file is there, owned by uid `10001`
- [ ] If `data/product-images` does not exist: `docker compose exec app ls /app/SupremeData`.
      If the file is there, the env var name in `compose.yaml` is wrong. Fix it before the
      first payment photo, which lands the same way under `SUPREME_PAYMENTPHOTO_PATH`.

---

## 5. Backups

`deploy/backup.sh` is the Mac's `scripts/backup.sh` with the paths and the tools swapped: the
dump is taken through `docker compose exec db`, verified with `pg_restore --list` through the
same container, and copied offsite with `rclone`. It holds no password — the superuser
connects over the container's local socket — which is why it is committed and `scripts/` is
not.

Two schedules, because the dump is ~140 KB and the photos are not:

- every 15 minutes: the dump, kept 48 hours (in `backups/`)
- 05:30 nightly, half an hour after the business day closes: the dump plus a tar of `./data`,
  kept 14 days (in `backups/nightly/`)

The 15-minute cadence is what bounds the loss from a dead VPS to one quarter-hour of trade.

**Offsite first.** Configure the rclone remote as `pos` (Google Drive, Backblaze B2, a
second VPS over sftp — anything rclone speaks):

```bash
rclone config          # follow the prompts; name the remote e.g. `gdrive`
rclone lsd gdrive:     # must list something without error
```

Put it in `.env`: `RCLONE_REMOTE=gdrive:SupremeBackups`. Until this is set, every run logs
`WARNING: no second location configured. This backup exists on ONE machine only.` — and a
backup on the machine it protects against is not a backup.

Run it by hand once, then prove the dump restores:

```bash
deploy/backup.sh
deploy/verify-backup.sh
```

`verify-backup.sh` restores the newest dump into a throwaway database inside the `db`
container, prints the figures it finds, drops the throwaway, and ends with `PASS` or a
`WARNING` explaining that the reference bill (630.00 / 157.50 / 472.50 — the trace from the
Mac's test data) is not in this database, which on a fresh VPS is expected. `FAILED` means the
dump did not restore; do not go further.

Install the schedule (`crontab -e` as `pos`):

```cron
# Supreme Billiards POS backups. Times are Asia/Manila (step 1 set the host zone).
*/15 * * * *  cd /opt/supreme && deploy/backup.sh           >> backups/cron.log 2>&1
30   5 * * *  cd /opt/supreme && deploy/backup.sh --nightly >> backups/cron.log 2>&1
```

(05:30 is also a quarter-hour slot, so both lines fire then. Two 140 KB dumps a night is
fine.) Check tomorrow: `tail backups/backup.log` shows a run every fifteen minutes and one
`nightly` at 05:30, and `rclone ls gdrive:SupremeBackups` shows the same files.

---

## 6. The restore drill — mandatory before the hall opens

This is GO-LIVE's "Rebuilding on new hardware", which has never been walked. Docker is what
makes walking it cheap: the whole procedure is a clone, a `.env`, a dump and one script, on any
machine with Docker — a second throwaway VPS, or a laptop.

On the throwaway machine:

```bash
git clone git@github.com:wilsonTiquia/supreme-billiards-hall.git supreme && cd supreme
# .env from the real box, then: SITE_ADDRESS=localhost and CADDY_LOCAL_CERTS=local_certs,
# so Caddy signs its own certificate instead of asking Let's Encrypt for a name this
# machine does not answer for.
scp pos@51.79.241.146:/opt/supreme/.env .env
sed -i 's/^SITE_ADDRESS=.*/SITE_ADDRESS=localhost/; s/^CADDY_LOCAL_CERTS=.*/CADDY_LOCAL_CERTS=local_certs/' .env
mkdir -p data backups && sudo chown 10001:10001 data
# The newest dump and the newest photo archive.
scp pos@51.79.241.146:/opt/supreme/backups/supreme-*.dump backups/
scp 'pos@51.79.241.146:/opt/supreme/backups/nightly/data-*.tar.gz' backups/nightly/ 2>/dev/null || true

docker compose up -d --build         # empty database, V1–V20 applied, app healthy
deploy/restore.sh                    # stops app, restores the dump over it, starts app
tar -xzf backups/nightly/data-*.tar.gz     # only if there is one; restores ./data
docker compose restart app           # picks up the restored photos
```

Then, at `https://localhost` (accept the self-signed certificate):

- [ ] Log in with the **real** owner password — it came with the dump, not with `.env`
- [ ] The catalogue is there; the product with the uploaded image shows it
- [ ] If a table was open when the dump was taken, it is open here and its elapsed time is
      right — counted from the session's start, not from when this machine came up. That is
      the proof that occupancy lives in `session_segment`/`session_pause` and nowhere in memory
- [ ] The dashboard shows the last closed night's figures

**Time it, from `git clone` to the login screen, and write the number here:**

- 16 September 2026, on the Mac under Docker Desktop, base images and build layers already
  cached: **46 seconds** from `docker compose up -d --build` to the app healthy after the
  restore, about two minutes including the copies. The login screen answered, the owner
  logged in with the password from the dump (not from `.env`), the product and its image were
  there, and a table opened 79 seconds before the check reported `billedSeconds: 79`, counted
  from its `openedAt` and not from when the new machine came up.
- On a cold VPS add the first-build time from section 2 — expect ten minutes, not one.
- **Not yet walked on a real second VPS.** Do it once, with the technician who would do it at
  midnight, and replace this line with the number.

Then `docker compose down -v` on the throwaway and delete the copied `.env` and dump.

---

## 7. Updating

```bash
cd /opt/supreme && git pull && docker compose up -d --build
```

The build takes a few minutes; only at the end does compose replace the `app` container, and
that is a restart of a few seconds. **Open tables are unaffected**: elapsed time is computed
from `session_segment` and `session_pause` timestamps in the database, and the amount charged
is recomputed from them at checkout. Nothing about a running session is held in the JVM.
Nobody logged in is logged out either — but a request in flight during those seconds fails,
so **do not update during service anyway.** Mornings, before the first table.

`git pull` also brings new migrations; Flyway applies them on the restart. A migration that
fails stops the app from starting — the log says which one — and the fix is a new migration,
never an edit to an applied one.

Kernel updates from `unattended-upgrades` need a reboot; `/var/run/reboot-required` exists
when one is pending. Reboot in the morning: the containers are `restart: unless-stopped` and
come back on their own. Confirm with `docker compose ps` afterwards.

---

## 8. What each failure costs

| Failure | What is lost |
|---|---|
| `app` restarts (update, crash, OOM) | Nothing. Open tables, bills, sessions are all in the database. Staff may need to log in again. |
| VPS reboots | Nothing. `restart: unless-stopped` brings all three containers back; the database volume and `./data` are on disk. |
| VPS gone (disk failure, OVH incident, deleted by mistake) | Everything after the last **offsite** dump — at most fifteen minutes of trade if `RCLONE_REMOTE` is set, and **everything** if it is not. Photos: everything after the last nightly. |
| `.env` lost | Nothing, if the owner wrote the passwords down: the database role already carries `DB_PASSWORD`, so the new `.env` must hold the same value, not a new one. |
| Caddy volumes lost | A new certificate is issued on the next start. Let's Encrypt allows five per week per name — do not `down -v` in a loop. |

---

## 9. Starting over on the VPS

**There is no reset script on the box, and there must not be one.** `scripts/reset-for-testing.sh`,
`scripts/reset-for-golive.sh` and `scripts/seed-demo.sh` are Mac tools: they carry the Mac's
database password and its temporary logins, `.gitignore` keeps them out of the repository, and
`.dockerignore` keeps them out of the image. They do not go to the VPS.

A VPS database is wiped by destroying its volume:

```bash
cd /opt/supreme
docker compose down -v        # stops all three containers and DELETES the volumes
```

That is a named, explicit, destructive command, not a keypress at a prompt. **It deletes every
sale, every user, every password hash, and Caddy's certificate.** It does not delete `./data`
(the photos) or `./backups`. There is no confirmation and no undo other than
`deploy/restore.sh` from a dump. Same warning the Mac scripts carry: never on a database
holding real takings unless you have just taken and verified a backup and you mean it.

After `down -v`, the next `docker compose up -d` is a first boot again: put
`SUPREME_BOOTSTRAP_ADMIN_PASSWORD` back in `.env` first, then follow section 2 from the `up`.

### The whole reset, in order

About two minutes end to end. Every line is typed by hand; nothing here asks "are you sure".

```bash
cd /opt/supreme
deploy/backup.sh --nightly        # ALWAYS, even in testing: it costs ten seconds and the
                                  # dump lands in ./backups, which the reset does not touch
docker compose down -v            # stops everything and DELETES the database volume
sudo rm -rf data/*                # product images and payment photos — a bind mount, so
                                  # down -v leaves them behind; omit this line to keep them
nano .env                         # put SUPREME_BOOTSTRAP_ADMIN_PASSWORD=<first password> back
docker compose up -d
docker compose logs app | grep -E 'profile is active|applied 20|Started Billiards|AdminPasswordBootstrap'
```

All four lines, as in section 2. Then in the browser: log in as `owner` with the bootstrap
password, change it, and back on the box delete the bootstrap line from `.env` and
`docker compose up -d` once more.

Two things `down -v` takes with it that are easy to forget:

- **Caddy's certificate.** The `caddy-data` volume goes too, so the next `up` asks Let's Encrypt
  for a new one — about a minute, during which the site shows a connection error. Let's Encrypt
  allows **five certificates per hostname per week**; reset more than a few times in a day and
  the site will be certificate-less until the window passes. If you expect to reset repeatedly
  while testing, use `docker compose down` (no `-v`) followed by
  `docker volume rm supreme_db-data` instead — that wipes only the database and keeps the
  certificate.
- **Every user, including the ones the owner created.** Staff accounts are rows in the
  database. After a reset the owner recreates them in Admin → Staff.

`./backups` survives. If the reset was a mistake, `deploy/restore.sh` with the dump you just
took puts everything back.

---

## 10. The demo data

If the owner wants the demo on the VPS before opening — to click around from home, to show
staff — it goes there **as a dump from the Mac**, never by running the seed there:

```bash
# On the Mac, with the demo loaded:
scripts/backup.sh
scp ~/SupremeBackups/supreme-<newest>.dump pos@51.79.241.146:/opt/supreme/backups/
# On the VPS:
deploy/restore.sh backups/supreme-<newest>.dump
```

The Mac's owner password comes with it. Before the hall opens, the demo must go:

1. `docker compose down -v`
2. put `SUPREME_BOOTSTRAP_ADMIN_PASSWORD` in `.env`
3. `docker compose up -d`, section 2 from the `up`: profile line, bootstrap line, log in,
   change the password, remove the variable
4. enter the catalogue (GO-LIVE, "The catalogue")
5. `deploy/backup.sh && deploy/verify-backup.sh`

---

## What this satisfies, by name

From `application-prod.properties`:

- Session cookie `Secure`, `SameSite=Strict`, `HttpOnly` — on, because the profile is active
  (section 2, line 1); TLS is terminated by Caddy, so the browser sends the cookie back
- Requirement 1, the proxy overwrites `X-Forwarded-For` — section 3, verified against the
  app's own log, not the proxy's documentation
- Requirement 2, the app's port is reachable only through the proxy — no `ports:` on `app`
- `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` from the environment — `compose.yaml` from `.env`

From GO-LIVE's hosting-day section:

- TLS-terminating proxy in front — Caddy
- `SPRING_PROFILES_ACTIVE=prod` and the log line confirming it — section 2
- The two proxy requirements — above
- Request body capped at the proxy — `request_body max_size 7MB` in the `Caddyfile`; an 8 MB
  POST got `413` from Caddy on the local run. The app's own `max-request-size=6MB` and
  `max-body-bytes` still apply beneath it
- Where payment photos live — `./data/payment-photos` on the host, tarred nightly by
  `deploy/backup.sh`, copied offsite by rclone; not on an ephemeral filesystem

From GO-LIVE elsewhere:

- Item 1, the bootstrap environment variable — closed for the VPS by compose (section 2)
- Item 13, the database unreachable from outside — no `ports:` on `db`
- "Rebuilding on new hardware" — section 6, walked when the time is written in
