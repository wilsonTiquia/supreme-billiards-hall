# How a change reaches the hall

For the owner, on the GitHub website. You do not need a terminal for anything on this page
except the one-time setup at the bottom.

```
  laptop                         GitHub                                    VPS
  ──────                         ──────                                    ───
  branch  ──push──▶  pull request ──▶ CI (backend + frontend) ──green──▶ you review
                                                                            │
                                                          "Squash and merge" ▼
                                                                          main
                                                                            │
                                                   Actions → Deploy → type "deploy"
                                                                            │
                                                                            ▼
                                             pos@51.79.241.146: deploy/update.sh
                                             git pull --ff-only && docker compose up -d --build
                                                                            │
                                     wait for https://billiards.frasi.tech/api/v1/time → 401
```

Two rules, and the rest of this page is the mechanics of them:

1. **Nothing lands on `main` except through a pull request that CI has passed.** Branch
   protection enforces it, for everyone including the account that set it up.
2. **The VPS is updated by one deliberate click, not by a push.** Merging changes GitHub.
   Only the Deploy button changes the hall, and it does exactly one thing.

---

## Branch names

| Prefix | For |
|---|---|
| `feat/<slug>` | something new the counter or the owner can use |
| `fix/<slug>` | a bug |
| `chore/<slug>` | everything else — docs, CI, dependencies, cleanup |

One branch, one pull request, one squash commit on `main`. Nobody commits on `main` directly;
the protection rule rejects the push if they try.

---

## Reviewing a pull request

1. Open the repository → **Pull requests** → click the one to review.
2. Read the description first. The template has four headings; **How it was tested** must
   name the command and the count it printed (`Tests run: 203, Failures: 0` today). If it
   says "not run", or the count is missing, that is the first comment to leave.
3. Check the two checks at the bottom of the **Conversation** tab: `backend` and `frontend`,
   both with a green tick. `backend` is the Java suite against a throwaway database (it
   builds the SPA first, because the suite serves the fallback page); `frontend` is
   `npm run typecheck` and `npm run build`. Red means do not merge; the
   **Details** link shows which test failed and why.
4. **Files changed** tab. Read the diff. Hover over a line and click the blue **+** to leave a
   comment on that line; a comment left this way stays attached to the line.
5. Top right, **Review changes** → one of:
   - **Comment** — questions, no decision yet
   - **Request changes** — not merging like this; the comments say what to change
   - **Approve** — good to go
6. New commits pushed to the branch in reply to your comments dismiss an earlier approval
   (that is the "dismiss stale approvals" setting) — look again before merging.

Playwright (the browser tests in `frontend/e2e`) is **not** run by CI yet. It needs a live
backend on port 8081 started by `scripts/e2e-backend.sh`, which in turn needs a local `psql`
and a local Postgres, and that is its own job to wire up. Until then, a PR that touches a screen
should say in its description whether `npm run e2e` was run on the laptop.

---

## Merging

Green checks, and you are happy with the diff: the green button at the bottom of the
Conversation tab. It reads **Squash and merge** — that is the only kind enabled, so one PR
becomes exactly one commit on `main`. Keep the title as the commit line (it is the PR title),
confirm, and the branch is deleted for you.

Merging does **not** deploy. The hall is still running whatever it ran this morning.

---

## Pressing Deploy

**Mornings, before the first table.** Open tables survive the restart (their timers live in
the database, not the program), but a request in flight during the few seconds the app is
replaced fails, and a payment mid-confirmation is not the moment.

1. **Actions** tab → **Deploy** in the left column → **Run workflow** (right side, grey
   button) → branch `main` → in the box type `deploy` → green **Run workflow**.
2. A run appears in the list; click it, then the `deploy` job, and watch. Three steps that
   matter:
   - *Run deploy/update.sh on the VPS* — prints `== /opt/supreme: <old> -> <new>`, then the
     Docker build. Three to five minutes.
   - *Wait for 401 from the health URL* — polls `https://billiards.frasi.tech/api/v1/time`
     every five seconds for up to ninety. `HTTP 401` means the new app is up and the login
     screen will answer. `HTTP 502` for the whole ninety seconds means it did not start.
3. Green: done. Open the site and log in once, as the first check the till would do.
4. Red on the health step: the app did not come up. The box is still reachable the old way
   (section 7 of `DEPLOY-VPS.md` has the `docker compose logs -f app` command). The usual
   cause is a migration that failed, and the log names it.

Typing anything other than `deploy` in the box fails the run on its first step and touches
nothing. Two clicks in a row queue behind each other rather than running at once.

---

## Rolling back

There is no "undeploy" button, on purpose: a rollback is a change like any other, so it goes
through the same review and the same button, and `main` always says what the hall is running.

1. On the laptop, on `main`, find the commit to undo (`git log --oneline`) and revert it on a
   branch:
   ```
   git switch main && git pull
   git switch -c fix/revert-<slug>
   git revert <sha>          # one commit per squash-merged PR, so this is one sha
   git push -u origin fix/revert-<slug>
   gh pr create
   ```
2. Review and merge it like any other PR. CI runs on it too — a revert that breaks the tests
   is telling you something.
3. Press **Deploy**.

A revert does not undo a database migration: Flyway applies forward only, and the
migration that came with the reverted code stays applied. That is usually fine (the old code
ignores the new column). When it is not, the fix is a further migration, never an edit to an
applied one — `CLAUDE.md` section 1.

---

## What enforces this

The repository is public on GitHub, so branch protection is available and is in place.
Recorded here so it can be re-applied after a repository rebuild, and so nobody has to guess
what it says. Both commands run as the repository owner with `gh` logged in.

Repository settings — squash is the only merge method, and merged branches are deleted:

```
gh api -X PATCH repos/wilsonTiquia/supreme-billiards-hall \
  -F allow_squash_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false \
  -F delete_branch_on_merge=true -F squash_merge_commit_title=PR_TITLE \
  -F squash_merge_commit_message=PR_BODY
```

Branch protection on `main`:

```
gh api -X PUT repos/wilsonTiquia/supreme-billiards-hall/branches/main/protection --input - <<'JSON'
{
  "required_status_checks": { "strict": true, "contexts": ["backend", "frontend"] },
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "required_approving_review_count": 0
  },
  "enforce_admins": true,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_linear_history": true
}
JSON
```

What each line buys:

- `required_status_checks` — the two CI jobs must be green. `strict` means the branch must
  also be up to date with `main`; if another PR merged first, the PR shows an **Update
  branch** button, one click, and CI runs again.
- `required_pull_request_reviews` — a pull request is mandatory. The approval count is 0
  because GitHub does not let an account approve its own PR, and every PR here is opened
  under the owner's account; a count of 1 would make merging impossible. The review is the
  owner reading the diff, and `dismiss_stale_reviews` still throws away an approval when new
  commits arrive.
- `enforce_admins` — the rule applies to the owner too. Without it the account that set the
  rule could push to `main` past it, and "nothing lands on main without a PR" would be a
  suggestion.
- `allow_force_pushes: false`, `allow_deletions: false`, `required_linear_history` — `main`
  is append-only and every commit on it is a squash of one PR.

To see it: `gh api repos/wilsonTiquia/supreme-billiards-hall/branches/main/protection`.

---

## Setting up the Deploy button (once)

The workflow needs two secrets and the VPS needs one line. None of it exists until this is
done; until then, the button fails on its second step with `secret VPS_SSH_KEY is not set`
and touches nothing.

**The key.** A new ed25519 pair made for this and nothing else — not the read-only deploy key
the VPS uses to pull from GitHub, and not the laptop key you log in with. On the laptop:

```
ssh-keygen -t ed25519 -N '' -f ~/.ssh/supreme_actions_deploy -C "github-actions-deploy -> pos@supreme-vps"
```

**On the VPS, as `pos`.** Append one line to `~/.ssh/authorized_keys`: the restriction, a
space, then the contents of `~/.ssh/supreme_actions_deploy.pub` from the laptop. All on one
line:

```
restrict,command="/opt/supreme/deploy/update.sh" ssh-ed25519 AAAA...the public key... github-actions-deploy -> pos@supreme-vps
```

`restrict` turns off port forwarding, agent forwarding, X11 and the pseudo-terminal;
`command=` makes sshd run `deploy/update.sh` no matter what the client asked for, and the
script itself refuses if the client asked for anything at all. So this key can update the hall
and cannot open a shell, read `.env`, or copy a file off the box. Check from the laptop:

```
ssh -i ~/.ssh/supreme_actions_deploy pos@51.79.241.146 "ls"    # must print "refused: ..." and exit 1
ssh -i ~/.ssh/supreme_actions_deploy pos@51.79.241.146          # must run the update
```

The second one **is a deploy** — it pulls and rebuilds — so run it in the morning like any
other.

**The two secrets.** Repository → **Settings** → **Secrets and variables** → **Actions** →
**New repository secret**, or from the laptop:

```
gh secret set VPS_SSH_KEY     < ~/.ssh/supreme_actions_deploy
ssh-keyscan -t ed25519 51.79.241.146 | gh secret set VPS_KNOWN_HOSTS
```

`VPS_KNOWN_HOSTS` is the VPS's own host key. With it pinned, the workflow keeps
`StrictHostKeyChecking=yes`, and a box at that address presenting a different key — someone
else's — is refused rather than trusted on first use. Confirm the fingerprint against
`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` run on the VPS before you paste it.

Once the secrets are in, the private key on the laptop has done its job. Delete it
(`rm ~/.ssh/supreme_actions_deploy`) or keep it somewhere the other passwords live; the
public half stays on the VPS, and the secret cannot be read back out of GitHub.

**Rotating it** is the same three steps with a new pair, then delete the old line from
`authorized_keys`.
