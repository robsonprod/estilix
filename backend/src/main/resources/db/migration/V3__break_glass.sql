-- Break-glass sessions of platform support (spec identity/support-access, design D10).
-- Tenant-owned so the salon's owners can list them; expiration is judged with the database clock.

create table break_glass_session (
    id              uuid primary key,
    tenant_id       uuid        not null references tenant (id),
    support_user_id uuid        not null,
    reason          text        not null check (length(reason) between 20 and 500),
    started_at      timestamptz not null default now(),
    expires_at      timestamptz not null,
    ended_at        timestamptz,
    status          text        not null default 'ACTIVE' check (status in ('ACTIVE', 'ENDED', 'EXPIRED')),
    unmasked_reads  integer     not null default 0,
    check (expires_at > started_at and expires_at <= started_at + interval '60 minutes')
);

-- At most one active session per support user and tenant.
create unique index break_glass_one_active on break_glass_session (support_user_id, tenant_id)
    where status = 'ACTIVE';
create index break_glass_tenant_time on break_glass_session (tenant_id, started_at desc);

alter table break_glass_session enable row level security;
alter table break_glass_session force row level security;
create policy break_glass_isolation on break_glass_session
    using (tenant_id = app_tenant_id()) with check (tenant_id = app_tenant_id());

grant select, insert, update on break_glass_session to estilix_app;
