# Render run: the same job, twice

Run on 2026-11-01T14:11:58.968Z against the hosted control plane (https://mutual-stake-api.onrender.com) and the PayPal sandbox. Verifier SHA-256 (`custom_id` on all four orders): `dcf42a28d65b2ab8abdfc108b3e86904d56e0a3cd7d56dc61b2112843aa5bc7d`.

The four holds were approved by hand in PayPal sandbox checkout. Everything after that was scripted by `docs/demo/sandbox-run.mjs`, including the session's one Confirm (US-16), which the driver pressed on the operator's behalf at 2026-11-01T14:11:33.424972Z for run 1. Run 2 settled inside the confirmed session without one.

## Run 1: junk delivery, FAIL

Contract `74ee0e6d-3400-4892-958b-cba0a8b96762`, final state **SETTLED**. Verdict: **FAIL** (12 words, not English and placeholder text detected.).

| Jar | Payer | Amount | Shows | Order | Authorization | Capture | Payout batch | Payout item |
|---|---|---|---|---|---|---|---|---|
| Buyer fee | alex | $1.50 | **RELEASED** | `0FJ235347E650690U` | `7NG700012N793370R` | — | — | — |
| Seller bond | maya | $0.40 | **COMPENSATION** | `8K456970GD594805W` | `79564928FC1372413` | `6A010046YR954963S` | `HKCQKJZG36CZS` | `DTR5KUFPKGVLW` |

Ledger (PayPal-confirmed only):

| Event | Direction | Amount | Counterparty | PayPal id |
|---|---|---|---|---|
| HOLD_AUTHORIZED | NONE | $1.50 | alex | `7NG700012N793370R` |
| HOLD_AUTHORIZED | NONE | $0.40 | maya | `79564928FC1372413` |
| HOLD_VOIDED | NONE | $1.50 | alex | `7NG700012N793370R` |
| PAYPAL_FEE | OUT | $0.40 | paypal | `6A010046YR954963S` |
| CAPTURE_COMPLETED | IN | $0.40 | maya | `6A010046YR954963S` |
| PAYOUT_FEE | OUT | $0.25 | paypal | `DTR5KUFPKGVLW` |
| PAYOUT_SUCCEEDED | OUT | $0.40 | alex | `DTR5KUFPKGVLW` |

PayPal calls (writes), each with its PayPal-Request-Id and debug id:

| Hold | Call | HTTP | PayPal-Request-Id | debug id |
|---|---|---|---|---|
| BUYER_FEE | create_order | 200 | `ms-0307bd60-489e-427f-ad68-2a80a2b3a232:create` | `ca53b7987293b` |
| SELLER_BOND | create_order | 200 | `ms-6b9d5ad9-18e8-4135-983a-f3379b327a85:create` | `ca53b798af80e` |
| BUYER_FEE | authorize_order | 201 | `ms-0307bd60-489e-427f-ad68-2a80a2b3a232:authorize` | `ca53b7c15e884` |
| SELLER_BOND | authorize_order | 201 | `ms-6b9d5ad9-18e8-4135-983a-f3379b327a85:authorize` | `ca53b7c557b5a` |
| BUYER_FEE | void_authorization | 200 | `ms-0307bd60-489e-427f-ad68-2a80a2b3a232:void` | `ca53b7d491f2b` |
| SELLER_BOND | capture_authorization | 201 | `ms-6b9d5ad9-18e8-4135-983a-f3379b327a85:capture` | `ca53b7d4dde97` |
| SELLER_BOND | create_payout | 201 | `ms-6b9d5ad9-18e8-4135-983a-f3379b327a85:payout` | `ca53b7d5dbf5b` |

Transitions: DRAFT→TERMS_LOCKED, TERMS_LOCKED→BOTH_HELD, BOTH_HELD→DELIVERED, DELIVERED→FAIL, FAIL→SETTLED.

## Run 2: the 50-word blurb, PASS

Contract `fdbb579c-66ce-4bc6-8bf8-a4ef03189926`, final state **SETTLED**. Verdict: **PASS** (50 words, English, no placeholder text.).

| Jar | Payer | Amount | Shows | Order | Authorization | Capture | Payout batch | Payout item |
|---|---|---|---|---|---|---|---|---|
| Buyer fee | alex | $1.50 | **PAID** | `7DB27354F5426183Y` | `2CR627048J715360L` | `79483922T12099646` | `YZ3K22SF8TATY` | `LQY97M7BM89UJ` |
| Seller bond | maya | $0.40 | **RELEASED** | `6RT93810MH727735M` | `5R829833W1452760U` | — | — | — |

Ledger (PayPal-confirmed only):

| Event | Direction | Amount | Counterparty | PayPal id |
|---|---|---|---|---|
| HOLD_AUTHORIZED | NONE | $0.40 | maya | `5R829833W1452760U` |
| HOLD_AUTHORIZED | NONE | $1.50 | alex | `2CR627048J715360L` |
| CAPTURE_COMPLETED | IN | $1.50 | alex | `79483922T12099646` |
| PAYPAL_FEE | OUT | $0.54 | paypal | `79483922T12099646` |
| HOLD_VOIDED | NONE | $0.40 | maya | `5R829833W1452760U` |
| PAYOUT_SUCCEEDED | OUT | $1.50 | maya | `LQY97M7BM89UJ` |
| PAYOUT_FEE | OUT | $0.25 | paypal | `LQY97M7BM89UJ` |

PayPal calls (writes), each with its PayPal-Request-Id and debug id:

| Hold | Call | HTTP | PayPal-Request-Id | debug id |
|---|---|---|---|---|
| SELLER_BOND | create_order | 200 | `ms-414dbbec-f115-4b93-8d72-d2b0225fb522:create` | `ca53b79a15fd2` |
| BUYER_FEE | create_order | 200 | `ms-b9e1f0ae-19bf-498c-9248-38a7cc3ff066:create` | `ca53b79a50e21` |
| SELLER_BOND | authorize_order | 201 | `ms-414dbbec-f115-4b93-8d72-d2b0225fb522:authorize` | `ca53b7ce69df8` |
| BUYER_FEE | authorize_order | 201 | `ms-b9e1f0ae-19bf-498c-9248-38a7cc3ff066:authorize` | `ca53b7d186837` |
| BUYER_FEE | capture_authorization | 201 | `ms-b9e1f0ae-19bf-498c-9248-38a7cc3ff066:capture` | `ca53b7d5fcaa5` |
| SELLER_BOND | void_authorization | 200 | `ms-414dbbec-f115-4b93-8d72-d2b0225fb522:void` | `ca53b7d6908fb` |
| BUYER_FEE | create_payout | 201 | `ms-b9e1f0ae-19bf-498c-9248-38a7cc3ff066:payout` | `ca53b7d71ded4` |

Transitions: DRAFT→TERMS_LOCKED, TERMS_LOCKED→BOTH_HELD, BOTH_HELD→DELIVERED, DELIVERED→PASS, PASS→SETTLED.
