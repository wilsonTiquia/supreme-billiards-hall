-- Who was on the table, written by the people who know.
--
-- The unpaid strip on the floor can say "Table 1 · ₱1,350.00" and nothing more, so three
-- unpaid bills on the same table read identically the next morning. This is the missing name.
--
-- A separate table rather than a column on table_session: there are many notes per session,
-- and each one carries its own author and its own time. A single column would be one name
-- with no author, silently overwritten by whoever typed in it last.
--
-- APPEND-ONLY, by the same trigger the stock ledger and the audit log use. This system's
-- anti-fraud posture is detection rather than prevention -- friend rates and voids are
-- recorded with their actor rather than blocked -- and a note about money owed that an
-- employee can quietly delete defeats that. A wrong note is corrected by a later note.

CREATE TYPE session_note_kind AS ENUM ('STAFF', 'SYSTEM');

COMMENT ON TYPE session_note_kind IS
  'STAFF is typed by a person; SYSTEM is written by the application at an event, currently settlement. The API never accepts the kind from the client, so a SYSTEM note cannot be forged by typing one.';

CREATE TABLE session_note (
  id         uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id  uuid              NOT NULL REFERENCES branch(id),
  session_id uuid              NOT NULL,
  kind       session_note_kind NOT NULL DEFAULT 'STAFF',
  body       text              NOT NULL,
  -- Resolved server-side from the authenticated user, never sent by the client. On a SYSTEM
  -- note this is whoever performed the event the note records -- the person who settled.
  author_id  uuid              NOT NULL REFERENCES app_user(id),
  created_at timestamptz       NOT NULL DEFAULT now(),

  -- Composite, carrying branch_id, like every other child of table_session. Deliberately NOT
  -- ON DELETE CASCADE, unlike session_pause: this table blocks DELETE by trigger, so a cascade
  -- would turn a parent delete into a restrict_violation at runtime instead of a clean refusal.
  CONSTRAINT session_note_session_fk
    FOREIGN KEY (branch_id, session_id) REFERENCES table_session (branch_id, id),
  -- Rejects the empty and the whitespace-only note here as well as in the service. 280
  -- characters is a couple of names and the context that makes them worth reading.
  CONSTRAINT session_note_body_chk
    CHECK (btrim(body) <> '' AND length(body) <= 280)
);

CREATE TRIGGER session_note_append_only
  BEFORE UPDATE OR DELETE ON session_note
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- The thread for one session in order, and the latest-note lookup the floor strip makes for
-- each unpaid bill. Postgres reads this index in either direction, so one covers both.
CREATE INDEX session_note_session_idx ON session_note (session_id, created_at);
-- "Every note this employee wrote", the same question audit_log_actor_idx answers.
CREATE INDEX session_note_author_idx  ON session_note (author_id, created_at DESC);

COMMENT ON TABLE session_note IS
  'Append-only notes attaching people to a session and therefore to its bill. UPDATE and DELETE are blocked by trigger: a note about who owes money must not be removable by the person who owes it a favour. Nothing here is ever pruned -- a year of these is what answers "who keeps playing on credit".';
COMMENT ON COLUMN session_note.body IS
  'Free text, typically the names on the table. Rendered escaped by the client; never HTML.';
COMMENT ON COLUMN session_note.author_id IS
  'Who wrote it, from the authenticated session. Never accepted from the request body.';

-- Deliberately not built yet: an amount owed and a settled_at. "Who owes us and how much" is
-- the request this feature will produce once it has been in use a month, and it lands as two
-- nullable columns added here by a later migration -- no reshaping, and nothing already
-- written has to change.
