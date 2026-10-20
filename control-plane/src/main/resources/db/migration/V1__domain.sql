-- Mutual Stake v1 domain.
--
-- Money is integer minor units (bigint), never a float. Currency is USD only.
-- Foreign keys never cascade: history cannot be deleted, so Reset demo (US-31)
-- archives by session instead of deleting rows.
--
-- Custom SQLSTATEs raised by this schema:
--   MS001  append-only table: UPDATE, DELETE or TRUNCATE rejected
--   MS002  hold does not match its contract (payer, amount or verifier hash)

-- The two seeded sandbox identities (US-29) live here; emails come from config at startup.
create table party (
    id           uuid primary key,
    handle       text        not null unique check (handle ~ '^[a-z][a-z0-9-]{1,31}$'),
    display_name text        not null,
    paypal_email text        not null unique,
    created_at   timestamptz not null default now()
);

-- A compiled test. The stored hash must be the SHA-256 of the stored source (US-18),
-- so the two can never drift apart; rows are immutable once written.
create table verifier (
    id          uuid primary key,
    source      text        not null,
    language    text        not null check (language in ('javascript')),
    sha256      text        not null unique
                            constraint verifier_sha256_matches_source
                                check (sha256 = encode(sha256(convert_to(source, 'UTF8')), 'hex')),
    compiled_at timestamptz not null default now()
);

create table contract (
    id            uuid primary key,
    buyer_id      uuid        not null references party (id),
    seller_id     uuid        not null references party (id),
    seller_type   text        not null check (seller_type in ('AGENT', 'HUMAN')),
    job_text      text        not null,
    fee_cap_minor bigint check (fee_cap_minor > 0),
    fee_minor     bigint check (fee_minor > 0),
    bond_minor    bigint check (bond_minor > 0),
    currency      text        not null default 'USD' check (currency = 'USD'),
    deadline_at   timestamptz,
    verifier_id   uuid references verifier (id),
    terms_hash    text check (terms_hash ~ '^[0-9a-f]{64}$'),
    state         text        not null default 'DRAFT'
                              check (state in ('DRAFT', 'TERMS_LOCKED', 'BOTH_HELD', 'DELIVERED', 'PASS', 'FAIL',
                                               'APPEALED', 'SETTLED', 'EXPIRED', 'CANCELLED')),
    version       integer     not null default 0,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint contract_parties_differ check (buyer_id <> seller_id),
    -- US-7: the bond is never above 50% of the fee.
    constraint contract_bond_at_most_half_fee check (bond_minor is null or fee_minor is null or bond_minor * 2 <= fee_minor),
    -- US-1: the agreed fee never exceeds the buyer's cap.
    constraint contract_fee_within_cap check (fee_minor is null or fee_cap_minor is null or fee_minor <= fee_cap_minor),
    -- Terms are only optional while negotiating.
    constraint contract_terms_set_after_draft check (state = 'DRAFT' or (fee_minor is not null and bond_minor is not null and deadline_at is not null
                               and verifier_id is not null and terms_hash is not null))
);

-- One PayPal authorization hold per side. The platform is the payee on both orders, so
-- either side may be captured and then paid out (fee to the seller, bond to the buyer).
--
-- State follows PayPal, never ahead of it (US-30, US-32):
--   PENDING -> ORDER_CREATED -> APPROVED -> HELD -> VOID_REQUESTED -> VOIDED
--                                               \-> CAPTURE_REQUESTED -> CAPTURED -> PAYOUT_REQUESTED -> PAID_OUT
--   PENDING / ORDER_CREATED / APPROVED -> ABANDONED (never held; nothing to void)
-- needs_attention is a flag, not a state, so the money stage is never lost.
create table hold (
    id                       uuid primary key,
    contract_id              uuid        not null references contract (id),
    side                     text        not null check (side in ('BUYER_FEE', 'SELLER_BOND')),
    payer_id                 uuid        not null references party (id),
    amount_minor             bigint      not null check (amount_minor > 0),
    currency                 text        not null default 'USD' check (currency = 'USD'),
    custom_id                text        not null check (custom_id ~ '^[0-9a-f]{64}$'),
    -- Base for deterministic PayPal-Request-Ids ({request_id}:{operation}); never reused (US-17, US-31).
    request_id               text        not null unique,
    paypal_order_id          text unique,
    approve_url              text,
    authorization_id         text unique,
    authorization_expires_at timestamptz,
    capture_id               text unique,
    captured_minor           bigint check (captured_minor > 0),
    payout_recipient_id      uuid references party (id),
    sender_batch_id          text unique,
    payout_batch_id          text unique,
    payout_item_id           text unique,
    state                    text        not null default 'PENDING'
                                         check (state in ('PENDING', 'ORDER_CREATED', 'APPROVED', 'HELD',
                                                          'VOID_REQUESTED', 'VOIDED', 'CAPTURE_REQUESTED', 'CAPTURED',
                                                          'PAYOUT_REQUESTED', 'PAID_OUT', 'ABANDONED')),
    needs_attention          boolean     not null default false,
    attention_reason         text,
    version                  integer     not null default 0,
    created_at               timestamptz not null default now(),
    updated_at               timestamptz not null default now(),
    unique (contract_id, side),
    constraint hold_approval_has_order check (state not in ('ORDER_CREATED', 'APPROVED') or paypal_order_id is not null),
    constraint hold_held_has_authorization check (state not in ('HELD', 'VOID_REQUESTED', 'VOIDED', 'CAPTURE_REQUESTED')
           or (paypal_order_id is not null and authorization_id is not null)),
    constraint hold_captured_has_capture check (state not in ('CAPTURED', 'PAYOUT_REQUESTED', 'PAID_OUT')
           or (authorization_id is not null and capture_id is not null and captured_minor is not null)),
    constraint hold_payout_has_request check (state not in ('PAYOUT_REQUESTED', 'PAID_OUT')
                                              or (sender_batch_id is not null and payout_recipient_id is not null)),
    constraint hold_paid_out_has_payout_ids check (state <> 'PAID_OUT' or (payout_batch_id is not null and payout_item_id is not null)),
    constraint hold_capture_within_amount check (captured_minor is null or captured_minor <= amount_minor),
    -- A payout never goes back to the payer: that would be a refund, and void is not a refund.
    constraint hold_payout_not_to_payer check (payout_recipient_id is null or payout_recipient_id <> payer_id),
    constraint hold_attention_has_reason check (needs_attention = (attention_reason is not null))
);

create table delivery (
    id             uuid primary key,
    contract_id    uuid        not null unique references contract (id),
    payload_ref    text        not null,
    payload_sha256 text        not null check (payload_sha256 ~ '^[0-9a-f]{64}$'),
    submitted_at   timestamptz not null default now(),
    verdict        text check (verdict in ('PASS', 'FAIL')),
    failing_check  text,
    trace_ref      text,
    verified_at    timestamptz,
    constraint delivery_verdict_has_time check ((verdict is null) = (verified_at is null)),
    -- US-26: a FAIL always cites the check that failed.
    constraint delivery_fail_cites_check check (verdict is distinct from 'FAIL' or failing_check is not null)
);

-- Money events PayPal has confirmed, from the platform's books. Append-only (enforced below).
-- PayPal's own fees are recorded too, so the books show what the platform absorbs.
create table ledger_entry (
    id           uuid primary key,
    contract_id  uuid        not null references contract (id),
    hold_id      uuid references hold (id),
    event_type   text        not null check (event_type in ('HOLD_AUTHORIZED', 'HOLD_VOIDED', 'CAPTURE_COMPLETED',
                                                            'PAYPAL_FEE', 'PAYOUT_SUCCEEDED', 'PAYOUT_FEE')),
    direction    text        not null check (direction in ('IN', 'OUT', 'NONE')),
    amount_minor bigint      not null check (amount_minor >= 0),
    currency     text        not null default 'USD' check (currency = 'USD'),
    counterparty text        not null,
    paypal_ref   text        not null,
    occurred_at  timestamptz not null,
    recorded_at  timestamptz not null default now(),
    -- The same PayPal confirmation (webhook and poll alike) is recorded once.
    constraint ledger_entry_once_per_confirmation unique (event_type, paypal_ref),
    constraint ledger_entry_direction_matches_event check ((event_type in ('HOLD_AUTHORIZED', 'HOLD_VOIDED') and direction = 'NONE')
        or (event_type = 'CAPTURE_COMPLETED' and direction = 'IN')
        or (event_type in ('PAYPAL_FEE', 'PAYOUT_SUCCEEDED', 'PAYOUT_FEE') and direction = 'OUT'))
);

create index ledger_entry_contract on ledger_entry (contract_id, occurred_at);

-- Transactional outbox: written in the same transaction as the state change that causes it,
-- executed by a scheduled poller with the hold's deterministic PayPal-Request-Ids.
create table outbox_event (
    id              uuid primary key,
    aggregate_id    uuid        not null,
    type            text        not null,
    payload         jsonb       not null,
    created_at      timestamptz not null default now(),
    published_at    timestamptz,
    attempts        integer     not null default 0,
    next_attempt_at timestamptz not null default now(),
    last_error      text
);

create index outbox_event_due on outbox_event (next_attempt_at) where published_at is null;

-- Append-only enforcement ------------------------------------------------------------

create function reject_mutation() returns trigger
    language plpgsql as
$$
begin
    raise exception '% is append-only: % rejected', tg_table_name, tg_op using errcode = 'MS001';
end
$$;

create trigger ledger_entry_append_only
    before update or delete on ledger_entry
    for each row execute function reject_mutation();

create trigger ledger_entry_no_truncate
    before truncate on ledger_entry
    for each statement execute function reject_mutation();

create trigger verifier_immutable
    before update or delete on verifier
    for each row execute function reject_mutation();

create trigger verifier_no_truncate
    before truncate on verifier
    for each statement execute function reject_mutation();

-- A hold must match its contract: the right payer, the agreed amount, and the verifier hash
-- as custom_id (US-18). Holds therefore cannot exist before terms are locked.
create function check_hold_matches_contract() returns trigger
    language plpgsql as
$$
declare
    c contract%rowtype;
    hash text;
begin
    select * into c from contract where id = new.contract_id;
    select sha256 into hash from verifier where id = c.verifier_id;
    if hash is null or new.custom_id <> hash then
        raise exception 'hold custom_id must equal the contract verifier sha256' using errcode = 'MS002';
    end if;
    if new.side = 'BUYER_FEE' and (new.payer_id <> c.buyer_id or new.amount_minor <> c.fee_minor) then
        raise exception 'buyer fee hold must be paid by the buyer for the agreed fee' using errcode = 'MS002';
    end if;
    if new.side = 'SELLER_BOND' and (new.payer_id <> c.seller_id or new.amount_minor <> c.bond_minor) then
        raise exception 'seller bond hold must be paid by the seller for the agreed bond' using errcode = 'MS002';
    end if;
    return new;
end
$$;

create trigger hold_matches_contract
    before insert or update of contract_id, side, payer_id, amount_minor, custom_id on hold
    for each row execute function check_hold_matches_contract();
