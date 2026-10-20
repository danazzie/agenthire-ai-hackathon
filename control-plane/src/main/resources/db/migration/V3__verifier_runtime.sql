-- The verifier at work: what it was compiled from, the exact bytes it judged, and a replayable
-- record of every run (US-6 evidence, US-20, US-26).

-- The structured test a verifier was compiled from. Offers accept only compiled verifiers.
alter table verifier
    add column spec jsonb;

-- Deliverables, stored as the exact bytes that were hashed and judged. Content-addressed and immutable.
create table blob (
    sha256     text primary key check (sha256 ~ '^[0-9a-f]{64}$'),
    media_type text        not null,
    size_bytes integer     not null check (size_bytes between 0 and 1048576),
    bytes      bytea       not null,
    created_at timestamptz not null default now(),
    constraint blob_hash_matches_bytes check (sha256 = encode(sha256(bytes), 'hex')),
    constraint blob_size_matches_bytes check (size_bytes = octet_length(bytes))
);

-- Every time a verifier ran: enough to replay it anywhere. The source hash is of the bytes actually sent
-- to the runtime; the harness hash and runtime version pin everything around it.
create table verifier_run (
    id              uuid primary key,
    verifier_id     uuid        not null references verifier (id),
    delivery_id     uuid references delivery (id),
    kind            text        not null check (kind in ('DELIVERY', 'DRY_RUN', 'SELF_TEST')),
    source_sha256   text        not null check (source_sha256 ~ '^[0-9a-f]{64}$'),
    harness_sha256  text        not null check (harness_sha256 ~ '^[0-9a-f]{64}$'),
    runtime         text        not null,
    input_sha256    text        not null check (input_sha256 ~ '^[0-9a-f]{64}$'),
    -- ERROR is not a verdict: a crash, timeout or malformed result decides nothing and moves no money.
    outcome         text        not null check (outcome in ('PASS', 'FAIL', 'ERROR')),
    checks          jsonb       not null default '[]'::jsonb,
    failing_check   text,
    summary         text,
    error           text,
    injection_flags text[]      not null default '{}',
    duration_ms     integer     not null check (duration_ms >= 0),
    ran_at          timestamptz not null default clock_timestamp(),
    constraint verifier_run_fail_cites_check check (outcome <> 'FAIL' or failing_check is not null),
    constraint verifier_run_error_has_reason check (outcome <> 'ERROR' or error is not null),
    constraint verifier_run_delivery_kind check ((kind = 'DELIVERY') = (delivery_id is not null))
);

create index verifier_run_delivery on verifier_run (delivery_id, ran_at);

alter table delivery
    add column injection_flags  text[]  not null default '{}',
    add column needs_attention  boolean not null default false,
    add column attention_reason text,
    add constraint delivery_attention_has_reason check (needs_attention = (attention_reason is not null)),
    add constraint delivery_payload_is_blob check (payload_ref = 'blob:' || payload_sha256),
    add constraint delivery_payload_stored foreign key (payload_sha256) references blob (sha256);

-- What was delivered never changes, and a verdict, once given, is final.
create function check_delivery_change() returns trigger
    language plpgsql as
$$
begin
    if (new.contract_id, new.payload_ref, new.payload_sha256, new.submitted_at, new.injection_flags)
        is distinct from (old.contract_id, old.payload_ref, old.payload_sha256, old.submitted_at, old.injection_flags) then
        raise exception 'a delivery cannot change once submitted' using errcode = 'MS004';
    end if;
    if old.verdict is not null and (new.verdict, new.failing_check, new.trace_ref, new.verified_at)
        is distinct from (old.verdict, old.failing_check, old.trace_ref, old.verified_at) then
        raise exception 'the verdict is final' using errcode = 'MS004';
    end if;
    return new;
end
$$;

create trigger delivery_change_check
    before update on delivery
    for each row execute function check_delivery_change();

create trigger blob_append_only
    before update or delete on blob
    for each row execute function reject_mutation();

create trigger blob_no_truncate
    before truncate on blob
    for each statement execute function reject_mutation();

create trigger verifier_run_append_only
    before update or delete on verifier_run
    for each row execute function reject_mutation();

create trigger verifier_run_no_truncate
    before truncate on verifier_run
    for each statement execute function reject_mutation();
