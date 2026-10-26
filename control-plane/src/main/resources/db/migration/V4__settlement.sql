-- Settlement: the verdict's plan, the human confirmation of a session's first capture (US-16), and a
-- place to say a contract needs attention.

alter table contract
    add column settlement_planned_at   timestamptz,
    add column settlement_confirmed_at timestamptz,
    add column needs_attention         boolean not null default false,
    add column attention_reason        text,
    add constraint contract_attention_has_reason check (needs_attention = (attention_reason is not null)),
    -- Money only moves after a verdict (or a no-show): never from a client call.
    add constraint contract_settles_after_verdict
        check (settlement_planned_at is null or state in ('PASS', 'FAIL', 'EXPIRED', 'APPEALED', 'SETTLED')),
    add constraint contract_confirmed_before_planned
        check (settlement_confirmed_at is null or settlement_planned_at is not null);
