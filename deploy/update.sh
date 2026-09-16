#!/usr/bin/env bash
#
# Updates the VPS to whatever main is now. This is the ONLY thing the GitHub Actions deploy key
# may run: its line in /home/pos/.ssh/authorized_keys carries
#
#   restrict,command="/opt/supreme/deploy/update.sh"
#
# so sshd runs this script whatever the client asked for, and the refusal below turns "whatever
# the client asked for" into "nothing at all". The key can update the hall's software and cannot
# open a shell, copy a file, or read .env. See docs/DEPLOY-VPS.md section 7 and docs/WORKFLOW.md.
#
# The commands are the ones section 7 has always said, plus --ff-only: a pull that would have to
# merge means someone committed on the box, and that is a thing to look at, not paper over.

set -euo pipefail

main() {
    # A forced command still sees what the client sent, in SSH_ORIGINAL_COMMAND. Anything there
    # is a caller trying to use this key as a login, so refuse rather than deploy on their behalf.
    if [ -n "${SSH_ORIGINAL_COMMAND:-}" ]; then
        printf 'refused: this key runs deploy/update.sh and nothing else (asked for: %s)\n' \
            "$SSH_ORIGINAL_COMMAND" >&2
        exit 1
    fi

    cd /opt/supreme
    before="$(git rev-parse --short HEAD)"
    git pull --ff-only
    after="$(git rev-parse --short HEAD)"
    echo "== /opt/supreme: $before -> $after"

    # The build takes minutes; the app container is replaced only at the end, a restart of a
    # few seconds. Open tables live in the database, not the JVM (section 7).
    docker compose up -d --build
    docker compose ps
}

# Wrapped in a function and called last, so bash has read the whole file before `git pull`
# rewrites it under us. Otherwise it would carry on from the old byte offset into the new text.
main "$@"
