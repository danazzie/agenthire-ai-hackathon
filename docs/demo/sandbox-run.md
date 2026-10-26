# Sandbox run: the same job, twice

Run on 2026-10-21T18:29:05.314Z against the PayPal sandbox. Verifier SHA-256 (`custom_id` on all four orders): `dcf42a28d65b2ab8abdfc108b3e86904d56e0a3cd7d56dc61b2112843aa5bc7d`.

The four holds were approved by hand in PayPal sandbox checkout. Everything after that was scripted by `docs/demo/sandbox-run.mjs`, including the session's one Confirm (US-16), which the driver pressed on the operator's behalf at 2026-10-21T18:28:36.779724Z for run 1. Run 2 settled inside the confirmed session without one.

## Run 1: junk delivery, FAIL

Contract `d6ec9f6d-baee-4674-85a0-7f2c63bf0598`, final state **SETTLED**. Verdict: **FAIL** (12 words, not English and placeholder text detected.).

| Jar | Payer | Amount | Shows | Order | Authorization | Capture | Payout batch | Payout item |
|---|---|---|---|---|---|---|---|---|
| Buyer fee | alex | $1.50 | **RELEASED** | `3RX18589DL167832S` | `5UJ73997JE132310G` | — | — | — |
| Seller bond | maya | $0.40 | **COMPENSATION** | `0BF802289H644951D` | `8H309074XE5036806` | `8BF31750PJ6699911` | `NWRN9LHRVB3EL` | `QZCVXK23ZP8M6` |

Ledger (PayPal-confirmed only):

| Event | Direction | Amount | Counterparty | PayPal id |
|---|---|---|---|---|
| HOLD_AUTHORIZED | NONE | $1.50 | alex | `5UJ73997JE132310G` |
| HOLD_AUTHORIZED | NONE | $0.40 | maya | `8H309074XE5036806` |
| CAPTURE_COMPLETED | IN | $0.40 | maya | `8BF31750PJ6699911` |
| PAYPAL_FEE | OUT | $0.40 | paypal | `8BF31750PJ6699911` |
| HOLD_VOIDED | NONE | $1.50 | alex | `5UJ73997JE132310G` |
| PAYOUT_SUCCEEDED | OUT | $0.40 | alex | `QZCVXK23ZP8M6` |
| PAYOUT_FEE | OUT | $0.25 | paypal | `QZCVXK23ZP8M6` |

PayPal calls (writes), each with its PayPal-Request-Id and debug id:

| Hold | Call | HTTP | PayPal-Request-Id | debug id |
|---|---|---|---|---|
| BUYER_FEE | create_order | 200 | `ms-4665f08a-cd04-41d9-9007-0583bce0f6e7:create` | `ca4e242d25ec4` |
| SELLER_BOND | create_order | 200 | `ms-4f291a6f-67a1-472c-948a-2fe9b6c59163:create` | `ca4e242d8bf46` |
| BUYER_FEE | authorize_order | 201 | `ms-4665f08a-cd04-41d9-9007-0583bce0f6e7:authorize` | `ca4e24fd42ea6` |
| SELLER_BOND | authorize_order | 201 | `ms-4f291a6f-67a1-472c-948a-2fe9b6c59163:authorize` | `ca4e2521ddb04` |
| SELLER_BOND | capture_authorization | 201 | `ms-4f291a6f-67a1-472c-948a-2fe9b6c59163:capture` | `ca4e252b56d07` |
| BUYER_FEE | void_authorization | 200 | `ms-4665f08a-cd04-41d9-9007-0583bce0f6e7:void` | `ca4e252c19a2b` |
| SELLER_BOND | create_payout | 201 | `ms-4f291a6f-67a1-472c-948a-2fe9b6c59163:payout` | `ca4e252cadd7a` |

Transitions: DRAFT→TERMS_LOCKED, TERMS_LOCKED→BOTH_HELD, BOTH_HELD→DELIVERED, DELIVERED→FAIL, FAIL→SETTLED.

## Run 2: the 50-word blurb, PASS

Contract `389cc740-9877-4259-8b9f-534ed1150a70`, final state **SETTLED**. Verdict: **PASS** (50 words, English, no placeholder text.).

| Jar | Payer | Amount | Shows | Order | Authorization | Capture | Payout batch | Payout item |
|---|---|---|---|---|---|---|---|---|
| Buyer fee | alex | $1.50 | **PAID** | `57B87179G5752872N` | `60N92516LX353430U` | `5G771532TW958971L` | `BXK6UUHSP9MSN` | `CFNJP5NXEZGXG` |
| Seller bond | maya | $0.40 | **RELEASED** | `1CW722624S0531844` | `9CU29388JW084384E` | — | — | — |

Ledger (PayPal-confirmed only):

| Event | Direction | Amount | Counterparty | PayPal id |
|---|---|---|---|---|
| HOLD_AUTHORIZED | NONE | $1.50 | alex | `60N92516LX353430U` |
| HOLD_AUTHORIZED | NONE | $0.40 | maya | `9CU29388JW084384E` |
| HOLD_VOIDED | NONE | $0.40 | maya | `9CU29388JW084384E` |
| PAYPAL_FEE | OUT | $0.54 | paypal | `5G771532TW958971L` |
| CAPTURE_COMPLETED | IN | $1.50 | alex | `5G771532TW958971L` |
| PAYOUT_FEE | OUT | $0.25 | paypal | `CFNJP5NXEZGXG` |
| PAYOUT_SUCCEEDED | OUT | $1.50 | maya | `CFNJP5NXEZGXG` |

PayPal calls (writes), each with its PayPal-Request-Id and debug id:

| Hold | Call | HTTP | PayPal-Request-Id | debug id |
|---|---|---|---|---|
| SELLER_BOND | create_order | 200 | `ms-2fab1393-f2e4-4796-926b-df0355611e14:create` | `ca4e242edfd37` |
| BUYER_FEE | create_order | 200 | `ms-4c227aae-57f1-4fba-acae-9bd845a9a982:create` | `ca4e242f2cb9a` |
| BUYER_FEE | authorize_order | 201 | `ms-4c227aae-57f1-4fba-acae-9bd845a9a982:authorize` | `ca4e250786841` |
| SELLER_BOND | authorize_order | 201 | `ms-2fab1393-f2e4-4796-926b-df0355611e14:authorize` | `ca4e2528b8e24` |
| SELLER_BOND | void_authorization | 200 | `ms-2fab1393-f2e4-4796-926b-df0355611e14:void` | `ca4e252cdf955` |
| BUYER_FEE | capture_authorization | 201 | `ms-4c227aae-57f1-4fba-acae-9bd845a9a982:capture` | `ca4e252d40873` |
| BUYER_FEE | create_payout | 201 | `ms-4c227aae-57f1-4fba-acae-9bd845a9a982:payout` | `ca4e252e449ef` |

Transitions: DRAFT→TERMS_LOCKED, TERMS_LOCKED→BOTH_HELD, BOTH_HELD→DELIVERED, DELIVERED→PASS, PASS→SETTLED.

## Checked against the sandbox

After the run, a read-only check asked PayPal for each id above instead of trusting our own books (2026-10-21, sandbox REST API):

| Id | What PayPal says |
|---|---|
| Run 1 fee authorization `5UJ73997JE132310G` | VOIDED, $1.50, `custom_id` = the verifier hash |
| Run 1 bond capture `8BF31750PJ6699911` | COMPLETED: gross $0.40, PayPal fee $0.40, net $0.00 |
| Run 1 payout item `QZCVXK23ZP8M6` | SUCCESS: $0.40 to the buyer's sandbox account, payout fee $0.25 |
| Run 2 bond authorization `9CU29388JW084384E` | VOIDED, $0.40, `custom_id` = the verifier hash |
| Run 2 fee capture `5G771532TW958971L` | COMPLETED: gross $1.50, PayPal fee $0.54, net $0.96 |
| Run 2 payout item `CFNJP5NXEZGXG` | SUCCESS: $1.50 to the seller's sandbox account, payout fee $0.25 |

The winner received the full amount both times; the platform absorbed PayPal's fees. At these sizes the fees are larger than the stakes: moving the $0.40 bond cost the platform $0.65 ($0.40 capture fee, $0.25 payout fee), and moving the $1.50 fee cost $0.79.
