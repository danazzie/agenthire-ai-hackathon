# US-4 spike: what it proved, what it changes

**Gate passed** on 2026-10-20, run `us4-20261020T203411-1c0644`. The ids, statuses and every check are in the generated report, [US-4-sandbox-run.md](US-4-sandbox-run.md). The full redacted call log, with a PayPal debug id on every call, is [us4-sandbox-run.json](us4-sandbox-run.json).

## Proved in the sandbox

- **Both sides hold, nobody is charged.** Orders v2 `AUTHORIZE` worked for the buyer's fee (order `8Y517589J6464915C`) and for the seller's bond (order `6AK65289E83342326`).
- **The hash travels with the money.** `custom_id` equals the verifier SHA-256 on both orders at creation, before either hold existed. It is also on both authorizations and on the bond capture.
- **FAIL branch, end to end:**
  - The fee authorization was voided. A later capture attempt was refused with `422 AUTHORIZATION_VOIDED`.
  - The bond capture `7BE03498X60788205` is COMPLETED for $0.40.
  - Payouts v1 batch `SFGM4ZYWWS2HE` paid exactly $0.40 to the buyer, and the item status is SUCCESS.
- **Authorizations last 29 days.** Created 2026-10-20, they expire 2026-11-18. This matches the README's "up to ~29 days".

## What changes in the control plane

1. **Payouts do not replay.** A second payout with the same `PayPal-Request-Id` and the same `sender_batch_id` returns `400 USER_BUSINESS_ERROR`, "Batch with given sender_batch_id already exists". So a retry cannot pay twice, but it doesn't return the original batch either.
   - Store `payout_batch_id` from the first response.
   - Treat the duplicate error as "the payout exists but its id is unknown". The hold goes to *needs attention* until the `PAYMENT.PAYOUTSBATCH.*` webhook, which carries `sender_batch_id`, supplies the id.
   - Never resend with a new `sender_batch_id`.
2. **Orders and Payments do replay.** Repeating a create, void or capture with the same request id returned the original result: the same order id, HTTP 200, and the same capture id. Authorize replay was not tested. When the response says a hold is already authorized, voided (`AUTHORIZATION_ALREADY_VOIDED`) or captured, treat the step as done.
3. **The order-create status is not `CREATED`.** When `payment_source.paypal.experience_context` is set, create returns HTTP 200 with status `PAYER_ACTION_REQUIRED`. The approval link has `rel: payer-action`, not `approve`.
4. **At demo amounts, PayPal's fees cost more than the bond.**
   - The $0.40 capture carried a $0.40 PayPal fee, so the net was $0.00, and the payout cost another $0.25. The platform absorbs $0.65 on every FAIL.
   - The PASS branch wasn't run. Expect a capture fee on the $1.50 plus the same $0.25 payout fee.
   - The payout pays the gross bond, as US-4 requires ("for the bond amount"). The README has to say that the platform absorbs PayPal's fees.
5. **The platform is the payee on both orders.**
   - PASS: capture the fee, pay it out to the seller with Payouts v1, and void the bond.
   - FAIL: void the fee, capture the bond, and pay it out to the buyer.
   - Either hold can carry a payout, which the `Hold.payout_batch_id` column already allows.
   - Disputes on any capture stay with the platform, so US-6 can answer them with `provide-evidence`.

## Not covered by this run

- **The PASS branch:** the same calls with the roles swapped. Step 5 exercises it.
- **Webhooks:** these need a public URL. Local development polls `GET` order and capture instead, and the Render deployment receives signed webhooks.
- **Authorize replay** (see 2).
