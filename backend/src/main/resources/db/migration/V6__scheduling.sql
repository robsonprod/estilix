-- Agenda: bookings and blocks share one table so one exclusion constraint forbids any overlap
-- between them (change scheduling, design D1/D6/D8).

-- Booking rules per salon ----------------------------------------------------------------------
alter table tenant
    add column booking_days_ahead            integer not null default 30  check (booking_days_ahead between 1 and 90),
    add column booking_min_notice_minutes    integer not null default 60  check (booking_min_notice_minutes between 0 and 1440),
    add column booking_cancel_notice_minutes integer not null default 120 check (booking_cancel_notice_minutes between 0 and 2880);

grant update (booking_days_ahead, booking_min_notice_minutes, booking_cancel_notice_minutes) on tenant to estilix_app;

-- Booking groups: one code and one manage link for chained services -----------------------------
create table booking_group (
    id         uuid primary key,
    tenant_id  uuid        not null references tenant (id),
    phone      text        not null, -- E.164, personal data
    code_hash  text        not null, -- HMAC-SHA256(pepper, tenant|phone|code)
    token_hash text        not null unique, -- SHA-256 of the manage-link token
    created_at timestamptz not null default now(),
    unique (tenant_id, id)
);
create index booking_group_phone on booking_group (tenant_id, phone);

-- Agenda entries -------------------------------------------------------------------------------
create table agenda_entry (
    id              uuid primary key,
    tenant_id       uuid        not null references tenant (id),
    kind            text        not null check (kind in ('BOOKING', 'BLOCK')),
    professional_id uuid        not null,
    service_id      uuid,
    group_id        uuid,
    starts_at       timestamptz not null,
    ends_at         timestamptz not null,
    status          text        not null default 'CONFIRMED' check (status in ('CONFIRMED', 'CANCELLED')),
    customer_name   text,
    customer_phone  text,
    origin          text check (origin in ('PUBLIC', 'STAFF')),
    block_reason    text check (block_reason is null or length(block_reason) <= 80),
    cancelled_by    text check (cancelled_by in ('CUSTOMER', 'STAFF')),
    cancelled_at    timestamptz,
    created_at      timestamptz not null default now(),
    check (starts_at < ends_at),
    check (
        (kind = 'BOOKING' and service_id is not null and group_id is not null and customer_name is not null
            and customer_phone is not null and origin is not null and block_reason is null)
        or
        (kind = 'BLOCK' and service_id is null and group_id is null and customer_name is null
            and customer_phone is null and origin is null and status = 'CONFIRMED')
    ),
    foreign key (tenant_id, professional_id) references professional (tenant_id, id),
    foreign key (tenant_id, service_id) references service (tenant_id, id),
    foreign key (tenant_id, group_id) references booking_group (tenant_id, id),
    -- First commit wins: two confirmed entries of the same professional can never overlap.
    constraint agenda_entry_no_overlap exclude using gist (
        professional_id with =,
        tstzrange(starts_at, ends_at, '[)') with &&
    ) where (status = 'CONFIRMED')
);
create index agenda_entry_tenant_time on agenda_entry (tenant_id, starts_at);
create index agenda_entry_group on agenda_entry (group_id);

do $$
declare t text;
begin
    foreach t in array array['booking_group', 'agenda_entry'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy tenant_isolation on %I using (tenant_id = app_tenant_id()) with check (tenant_id = app_tenant_id())', t);
    end loop;
end $$;

grant select, insert on booking_group to estilix_app;
grant select, insert, update, delete on agenda_entry to estilix_app;

-- Public pages resolve a slug to an ACTIVE salon without exposing the tenant table (design D4).
create function active_tenant_id(p_slug text) returns uuid
    language sql stable security definer set search_path = public
as $$ select id from tenant where slug = p_slug and status = 'ACTIVE' $$;
revoke all on function active_tenant_id(text) from public;
grant execute on function active_tenant_id(text) to estilix_app;

-- Manage links resolve their salon by the token hash (the request has no login and no tenant yet).
create function booking_group_tenant(p_token_hash text) returns uuid
    language sql stable security definer set search_path = public
as $$
    select g.tenant_id from booking_group g join tenant t on t.id = g.tenant_id
    where g.token_hash = p_token_hash and t.status = 'ACTIVE'
$$;
revoke all on function booking_group_tenant(text) from public;
grant execute on function booking_group_tenant(text) to estilix_app;

-- Entering phone + code issues a fresh manage link (the previous one stops working).
grant update (token_hash) on booking_group to estilix_app;
