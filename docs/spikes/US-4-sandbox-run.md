# US-4 sandbox spike: us4-20261020T203411-1c0644

**GATE PASSED.** Both holds were placed, the fee was voided, the bond was captured and paid out to the buyer, all in the PayPal sandbox.

- Started 2026-10-20T20:34:11.370Z, finished 2026-10-20T20:40:23.875Z
- PayPal sandbox app `APP-4U835986JW767621P`; Payouts scope on token: yes
- Verifier SHA-256 (spike stand-in source), written as `custom_id` on every order: `906a15f04468dd6dfd9b898590f12551c73f3c32b21172a6004e144b4e678727`
- Script: [us4-sandbox.mjs](us4-sandbox.mjs). Every call, status and PayPal debug id: [us4-sandbox-run.json](us4-sandbox-run.json) (payer names, emails and addresses redacted; ids kept).

## Holds

| Hold | Payer → payee | Amount | Order | Authorization | Capture | Final state (GET order) |
|---|---|---|---|---|---|---|
| FEE | buyer → platform | 1.50 USD | `8Y517589J6464915C` | `07124535GU966511S` | — | order COMPLETED, auth VOIDED |
| BOND | seller → platform | 0.40 USD | `6AK65289E83342326` | `6B127203P6458315P` | `7BE03498X60788205` | order COMPLETED, auth CAPTURED, capture COMPLETED |

## Bond compensation (FAIL branch)

| Step | Id | Status |
|---|---|---|
| Capture bond | `7BE03498X60788205` | COMPLETED |
| Amount read back from the capture | 0.40 USD | — |
| Payouts v1 batch to buyer | `SFGM4ZYWWS2HE` | SUCCESS |
| Payout item | `EWAXQE5VL83US` | SUCCESS (0.40 USD) |
| Payout transaction | `94233477UR997934L` | — |
| Payout replay (same request id + sender_batch_id) | — | HTTP 400 USER_BUSINESS_ERROR |

PayPal's fee on the bond capture (from `seller_receivable_breakdown`): gross 0.40, fee 0.40, net 0.00. The payout pays the gross bond; the platform absorbs the fee.

## Checks

| Result | Check | Evidence |
|---|---|---|
| PASS | Verifier hash is on both orders (custom_id) before either hold exists | FEE 8Y517589J6464915C, BOND 6AK65289E83342326 |
| PASS | Replaying order create with the same PayPal-Request-Id returns the same order, not a second one | replay returned 8Y517589J6464915C |
| PASS | FEE approved by the buyer sandbox account | status APPROVED, payer matches buyer |
| PASS | BOND approved by the seller sandbox account | status APPROVED, payer matches seller |
| PASS | Both holds are authorizations, neither captured | FEE auth 07124535GU966511S CREATED, BOND auth 6B127203P6458315P CREATED |
| PASS | custom_id on both authorizations equals the verifier SHA-256 | FEE match, BOND match |
| PASS | FEE authorization is VOIDED (buyer never charged) | status VOIDED |
| PASS | Replaying the void with the same PayPal-Request-Id is harmless | replay HTTP 200 |
| PASS | A voided fee cannot be captured afterwards | HTTP 422 AUTHORIZATION_VOIDED |
| PASS | Replaying the capture with the same PayPal-Request-Id returns the same capture, not a second one | replay returned 7BE03498X60788205 |
| PASS | custom_id on the bond capture equals the verifier SHA-256 | match |
| PASS | Bond capture is COMPLETED for the full bond | capture 7BE03498X60788205 COMPLETED 0.40 USD |
| PASS | Replaying the payout cannot pay twice | replay refused: HTTP 400 USER_BUSINESS_ERROR |
| PASS | Bond paid out to the buyer for exactly the captured amount | batch SFGM4ZYWWS2HE SUCCESS, item EWAXQE5VL83US SUCCESS 0.40 |

## Why the platform is the payee on both orders

Every capture lands on the platform's sandbox business account first (US-4 says so for the bond; PASS does the same for the fee, then pays the seller with Payouts v1). That keeps any PayPal dispute on a capture the platform owns, so the appeal (US-6) can answer it with `provide-evidence` using the platform's own credentials. A fee payable straight to the seller would put a buyer's dispute in the seller's account, out of the platform's reach.
