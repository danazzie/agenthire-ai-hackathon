# Buyer and seller desks

One React app, one control plane. Three desks: buyer (Alex), seller (Maya), judge (today’s three-pane view).

## Routes

| Path | Screen |
|---|---|
| `/` | Role picker: I am the buyer, I am the seller. Judge is a quieter third choice. |
| `/buyer` | Buyer home: post a job, this session’s deals. |
| `/seller` | Seller home: deals waiting, no job draft. |
| `/judge` | Judge home: current home (job draft, deals, Reset demo). |
| `/buyer/contracts/:id` | Buyer desk for that deal. |
| `/seller/contracts/:id` | Seller desk for that deal. |
| `/judge/contracts/:id` | Three panes: buyer · jars · seller (US-21). |
| `/contracts/:id` | Uses the last chosen role; if none, the picker. |
| `/gallery` | Fixtures, still the judge layout. |

`/buyer` and `/seller` set `localStorage` (`mutualstake.role`) so `/contracts/:id` has a fallback. The path wins, so two browser windows can run buyer and seller at once.

`X-Party` is `alex` on the buyer desk and `maya` on the seller desk. No new API, no login.

## Buyer desk

Both jars stay visible. Next action only:

- Post and confirm the job (home).
- Approve the fee hold. Nothing is charged.
- Wait for Maya’s bond and delivery.
- Confirm the session’s first capture (US-16).
- No Maya-approve, no deliver buttons.

## Seller desk

Both jars stay visible. Next action only:

- Read the locked job and test.
- Approve the bond. Released if the test passes.
- Deliver (agent / junk / 50-word).
- No Alex-approve, no job draft.

## Judge

Header **Judge view** opens `/judge` or `/judge/contracts/:id` and does not change the stored role. Reset demo lives here.

## Copy

PayPal authorization holds, not escrow. Void ≠ refund. Parties are **Alex’s agent** and **Maya’s agent**.
