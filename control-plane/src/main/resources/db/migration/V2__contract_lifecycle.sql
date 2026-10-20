-- Contract lifecycle: sessions, negotiation, the legal transitions, and PayPal evidence.
--
-- New SQLSTATEs:
--   MS001  (also) a contract, hold, session or delivery row cannot be deleted
--   MS003  illegal contract or hold transition
--   MS004  contract terms changed after they were locked

-- A demo run, and the spending mandate it carries (US-16, US-25, US-31).
-- Reset demo archives the current session and starts a new one; nothing is deleted.
create table session (
    id                         uuid primary key,
    started_at                 timestamptz not null default now(),
    archived_at                timestamptz,
    cap_minor                  bigint      not null check (cap_minor > 0),
    max_jobs                   integer     not null check (max_jobs > 0),
    -- US-16: the first capture of a session waits for a human; this records the confirmation.
    first_capture_confirmed_at timestamptz
);

create unique index session_one_current on session ((true)) where archived_at is null;

-- No contract rows exist before this migration, so the column can be required outright.
alter table contract
    add column session_id uuid not null references session (id);

-- Identical terms must compare identical: deadlines are whole UTC seconds.
alter table contract
    add constraint contract_deadline_whole_seconds
        check (deadline_at is null or deadline_at = date_trunc('second', deadline_at));

-- Negotiation log (US-8). Each message carries complete terms; terms lock only when both
-- parties' latest messages are ACCEPTs of identical terms.
create table offer (
    id          uuid primary key,
    contract_id uuid        not null references contract (id),
    party_id    uuid        not null references party (id),
    kind        text        not null check (kind in ('PROPOSE', 'ACCEPT')),
    fee_minor   bigint      not null check (fee_minor > 0),
    bond_minor  bigint      not null check (bond_minor > 0),
    deadline_at timestamptz not null,
    verifier_id uuid        not null references verifier (id),
    note        text,
    created_at  timestamptz not null default clock_timestamp(),
    constraint offer_bond_at_most_half_fee check (bond_minor * 2 <= fee_minor),
    constraint offer_deadline_whole_seconds check (deadline_at = date_trunc('second', deadline_at))
);

create index offer_contract on offer (contract_id, created_at);

-- Every contract state change, with its cause and time (the evidence packet's timestamps, US-6).
create table contract_transition (
    id          uuid primary key,
    contract_id uuid        not null references contract (id),
    from_state  text        not null,
    to_state    text        not null,
    cause       text        not null,
    occurred_at timestamptz not null default clock_timestamp()
);

create index contract_transition_contract on contract_transition (contract_id, occurred_at);

-- The only legal contract transitions. Kotlin holds the same list (ContractState) and a test
-- keeps the two identical; this trigger rejects anything else, whatever code path tries it.
create table contract_transition_rule (
    from_state text not null,
    to_state   text not null,
    primary key (from_state, to_state)
);

insert into contract_transition_rule (from_state, to_state)
values ('DRAFT', 'TERMS_LOCKED'),
       ('TERMS_LOCKED', 'BOTH_HELD'),
       ('TERMS_LOCKED', 'CANCELLED'),
       ('BOTH_HELD', 'DELIVERED'),
       ('BOTH_HELD', 'EXPIRED'),
       ('DELIVERED', 'PASS'),
       ('DELIVERED', 'FAIL'),
       ('PASS', 'SETTLED'),
       ('FAIL', 'SETTLED'),
       ('PASS', 'APPEALED'),
       ('FAIL', 'APPEALED'),
       ('APPEALED', 'SETTLED'),
       ('EXPIRED', 'SETTLED');

-- The legal hold transitions, same idea. A hold never goes from released back to captured.
create table hold_transition_rule (
    from_state text not null,
    to_state   text not null,
    primary key (from_state, to_state)
);

insert into hold_transition_rule (from_state, to_state)
values ('PENDING', 'ORDER_CREATED'),
       ('PENDING', 'ABANDONED'),
       ('ORDER_CREATED', 'APPROVED'),
       ('ORDER_CREATED', 'ABANDONED'),
       ('APPROVED', 'HELD'),
       ('APPROVED', 'VOID_REQUESTED'),
       ('APPROVED', 'ABANDONED'),
       -- An authorize already in flight when the deal was cancelled: the late hold is voided.
       ('ABANDONED', 'VOID_REQUESTED'),
       ('HELD', 'VOID_REQUESTED'),
       ('HELD', 'CAPTURE_REQUESTED'),
       ('VOID_REQUESTED', 'VOIDED'),
       ('CAPTURE_REQUESTED', 'CAPTURED'),
       ('CAPTURED', 'PAYOUT_REQUESTED'),
       ('PAYOUT_REQUESTED', 'PAID_OUT');

create function check_contract_change() returns trigger
    language plpgsql as
$$
begin
    if new.state <> old.state and not exists (select 1
                                              from contract_transition_rule r
                                              where r.from_state = old.state
                                                and r.to_state = new.state) then
        raise exception 'illegal contract transition % -> %', old.state, new.state using errcode = 'MS003';
    end if;
    if new.session_id is distinct from old.session_id
        or (old.state <> 'DRAFT'
            and (new.buyer_id, new.seller_id, new.seller_type, new.job_text, new.fee_cap_minor, new.fee_minor,
                 new.bond_minor, new.currency, new.deadline_at, new.verifier_id, new.terms_hash)
                is distinct from
                (old.buyer_id, old.seller_id, old.seller_type, old.job_text, old.fee_cap_minor, old.fee_minor,
                 old.bond_minor, old.currency, old.deadline_at, old.verifier_id, old.terms_hash)) then
        raise exception 'contract terms are locked (state %)', old.state using errcode = 'MS004';
    end if;
    return new;
end
$$;

create trigger contract_change_check
    before update on contract
    for each row execute function check_contract_change();

create function check_hold_transition() returns trigger
    language plpgsql as
$$
begin
    if new.state <> old.state and not exists (select 1
                                              from hold_transition_rule r
                                              where r.from_state = old.state
                                                and r.to_state = new.state) then
        raise exception 'illegal hold transition % -> %', old.state, new.state using errcode = 'MS003';
    end if;
    return new;
end
$$;

create trigger hold_transition_check
    before update of state on hold
    for each row execute function check_hold_transition();

-- Every PayPal call, as evidence a judge can match against the sandbox (US-22) and with the
-- debug id PayPal returns on errors (US-32).
create table paypal_call (
    id          uuid primary key,
    hold_id     uuid references hold (id),
    operation   text        not null,
    method      text        not null,
    path        text        not null,
    request_id  text,
    http_status integer,
    debug_id    text,
    error_name  text,
    error_issue text,
    duration_ms integer     not null check (duration_ms >= 0),
    called_at   timestamptz not null default clock_timestamp()
);

create index paypal_call_hold on paypal_call (hold_id, called_at);

-- Webhook deliveries, deduplicated by PayPal's event id. Only verified events are acted on.
create table paypal_webhook_event (
    id            text primary key,
    event_type    text        not null,
    resource_type text,
    resource_id   text,
    verified      boolean     not null,
    payload       jsonb       not null,
    received_at   timestamptz not null default now(),
    processed_at  timestamptz
);

-- Outbox: a lease so a crashed worker's claim expires, a dead letter for errors no retry fixes,
-- and at most one live event per aggregate and type, so the webhook, the poll and the
-- return-page nudge can all ask for the same step without it running twice.
alter table outbox_event
    add column locked_until timestamptz,
    add column dead_at      timestamptz;

drop index outbox_event_due;

create index outbox_event_due on outbox_event (next_attempt_at) where published_at is null and dead_at is null;

create unique index outbox_event_one_live on outbox_event (aggregate_id, type)
    where published_at is null and dead_at is null;

-- History is append-only.
create trigger offer_append_only
    before update or delete on offer
    for each row execute function reject_mutation();

create trigger offer_no_truncate
    before truncate on offer
    for each statement execute function reject_mutation();

create trigger contract_transition_append_only
    before update or delete on contract_transition
    for each row execute function reject_mutation();

create trigger contract_transition_no_truncate
    before truncate on contract_transition
    for each statement execute function reject_mutation();

create trigger paypal_call_append_only
    before update or delete on paypal_call
    for each row execute function reject_mutation();

create trigger paypal_call_no_truncate
    before truncate on paypal_call
    for each statement execute function reject_mutation();

create trigger contract_transition_rule_fixed
    before update or delete on contract_transition_rule
    for each row execute function reject_mutation();

create trigger hold_transition_rule_fixed
    before update or delete on hold_transition_rule
    for each row execute function reject_mutation();

-- Rows that change state but are never deleted: history stays, Reset demo archives by session.
create function reject_delete() returns trigger
    language plpgsql as
$$
begin
    raise exception '% rows are never deleted', tg_table_name using errcode = 'MS001';
end
$$;

create trigger session_no_delete
    before delete on session
    for each row execute function reject_delete();

create trigger contract_no_delete
    before delete on contract
    for each row execute function reject_delete();

create trigger hold_no_delete
    before delete on hold
    for each row execute function reject_delete();

create trigger delivery_no_delete
    before delete on delivery
    for each row execute function reject_delete();
