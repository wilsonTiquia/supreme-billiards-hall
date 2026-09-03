-- The checkout transition is on by default now that it is authored rather than filmed.
--
-- V7 seeded this false because the asset at the time was a 3.9-second static frame with half a
-- second of blackout in the middle, and that is not something to switch on for a Saturday shift
-- unseen. What ships now is a 600ms morph — the bill card travelling and narrowing into the
-- receipt — with the receipt live and clickable throughout. That is not the same risk, so it
-- gets the default the owner asked for.
--
-- The switch itself is unchanged: Admin > Settings turns it off with no restart and no deploy.
UPDATE branch_setting SET value = 'true'::jsonb, updated_at = now()
WHERE key = 'checkout_animation';
