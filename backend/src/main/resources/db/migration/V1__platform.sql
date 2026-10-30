-- Platform tables and the tenant isolation model (design D1-D4).
--
-- The application sets these transaction-local settings right after BEGIN:
--   app.tenant_id  current salon        app.user_id  current internal user
--   app.subject    identity-provider id app.support  'on' for platform support
-- After a transaction ends, a setting that was used in the session reads as '' (not NULL),
-- so every accessor goes through nullif(...) before casting.

create function app_tenant_id() returns uuid language sql stable
as $$ select nullif(current_setting('app.tenant_id', true), '')::uuid $$;

create function app_user_id() returns uuid language sql stable
as $$ select nullif(current_setting('app.user_id', true), '')::uuid $$;

create function app_subject() returns text language sql stable
as $$ select nullif(current_setting('app.subject', true), '') $$;

create function app_is_support() returns boolean language sql stable
as $$ select coalesce(nullif(current_setting('app.support', true), ''), 'off') = 'on' $$;

-- Tenants ------------------------------------------------------------------------------------

create table tenant (
    id                 uuid primary key,
    name               text        not null check (length(name) between 1 and 120),
    establishment_type text        not null check (establishment_type in ('SALON', 'BARBERSHOP')),
    time_zone          text        not null default 'America/Sao_Paulo',
    status             text        not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED')),
    created_at         timestamptz not null default now()
);

-- Users ----------------------------------------------------------------------------------------
-- No tenant_id: a person can belong to several salons. RLS is enabled but NOT forced, so the
-- SECURITY DEFINER lookup below (run as the owner) can find a user by e-mail without exposing
-- the table to the application.

create table app_user (
    id           uuid primary key,
    subject      text        not null unique,
    email        text,
    display_name text,
    phone        text,
    created_at   timestamptz not null default now()
);

-- Memberships ----------------------------------------------------------------------------------

create table membership (
    tenant_id  uuid        not null references tenant (id),
    user_id    uuid        not null references app_user (id),
    role       text        not null check (role in ('OWNER', 'MANAGER', 'PROFESSIONAL')),
    created_at timestamptz not null default now(),
    primary key (tenant_id, user_id)
);
create index membership_user on membership (user_id);

-- Policies -------------------------------------------------------------------------------------

alter table tenant enable row level security;
alter table tenant force row level security;
create policy tenant_visible on tenant for select using (
    id = app_tenant_id()
    or app_is_support()
    or exists (select 1 from membership m where m.tenant_id = tenant.id and m.user_id = app_user_id())
);

alter table membership enable row level security;
alter table membership force row level security;
create policy membership_read on membership for select
    using (tenant_id = app_tenant_id() or user_id = app_user_id());
create policy membership_insert on membership for insert
    with check (tenant_id = app_tenant_id());
create policy membership_update on membership for update
    using (tenant_id = app_tenant_id()) with check (tenant_id = app_tenant_id());
create policy membership_delete on membership for delete
    using (tenant_id = app_tenant_id());

alter table app_user enable row level security;
create policy app_user_read on app_user for select using (
    subject = app_subject()
    or id = app_user_id()
    or exists (select 1 from membership m where m.user_id = app_user.id and m.tenant_id = app_tenant_id())
);
create policy app_user_insert on app_user for insert with check (subject = app_subject());
create policy app_user_update on app_user for update
    using (id = app_user_id()) with check (id = app_user_id());

-- Find a user id by e-mail without letting the application browse the user table.
create function find_user_id_by_email(p_email text) returns uuid
    language sql stable security definer set search_path = public
as $$ select id from app_user where lower(email) = lower(p_email) limit 1 $$;
revoke all on function find_user_id_by_email(text) from public;

-- Grants for the runtime role -----------------------------------------------------------------

grant select on tenant to estilix_app;
grant select, insert, update on app_user to estilix_app;
grant select, insert, update, delete on membership to estilix_app;
grant execute on function find_user_id_by_email(text) to estilix_app;
grant execute on function app_tenant_id(), app_user_id(), app_subject(), app_is_support() to estilix_app;
