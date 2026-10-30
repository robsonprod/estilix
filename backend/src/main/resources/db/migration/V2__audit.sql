-- Append-only audit trail (spec platform/audit-log, design D4 and D11).
-- tenant_id is NULL only for platform-level events (e.g. a user's first sign-in), which only
-- platform support can read.

create table audit_event (
    id                     uuid primary key,
    tenant_id              uuid references tenant (id),
    occurred_at            timestamptz not null default now(),
    actor_user_id          uuid,
    action                 text        not null,
    target_type            text,
    target_id              text,
    details                jsonb       not null default '{}'::jsonb,
    break_glass_session_id uuid
);
create index audit_event_tenant_time on audit_event (tenant_id, occurred_at desc);

alter table audit_event enable row level security;
alter table audit_event force row level security;
create policy audit_read on audit_event for select
    using (tenant_id = app_tenant_id() or (tenant_id is null and app_is_support()));
create policy audit_insert on audit_event for insert
    with check (tenant_id = app_tenant_id() or tenant_id is null);

-- Defense in depth: even the owner cannot rewrite history without a reviewed migration.
create function audit_event_is_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'audit_event is append-only' using errcode = '42501';
end $$;
create trigger audit_event_no_update before update or delete on audit_event
    for each row execute function audit_event_is_append_only();

grant select, insert on audit_event to estilix_app;
