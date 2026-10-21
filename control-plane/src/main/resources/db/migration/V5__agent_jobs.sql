-- A deal the agents negotiate: the person's own sentence, and the test and deadline they confirmed (US-1).
create table agent_job (
    contract_id uuid primary key references contract (id),
    sentence    text        not null,
    verifier_id uuid        not null references verifier (id),
    deadline_at timestamptz not null check (deadline_at = date_trunc('second', deadline_at)),
    created_at  timestamptz not null default now()
);

create trigger agent_job_append_only
    before update or delete on agent_job
    for each row execute function reject_mutation();
