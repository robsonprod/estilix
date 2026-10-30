-- Opening hours, services and professionals (change salon-setup, design D5/D6).
-- Times are minutes since midnight in the salon's own time zone; weekday is ISO (1 = Monday).
-- Requires btree_gist (created by the database init script as superuser).

-- Opening hours --------------------------------------------------------------------------------
create table opening_hours (
    tenant_id     uuid     not null references tenant (id),
    weekday       smallint not null check (weekday between 1 and 7),
    starts_minute integer  not null check (starts_minute between 0 and 1425 and starts_minute % 15 = 0),
    ends_minute   integer  not null check (ends_minute between 15 and 1440 and ends_minute % 15 = 0),
    check (starts_minute < ends_minute),
    -- No overlapping intervals on the same day of the same salon, enforced by the database.
    constraint opening_hours_no_overlap exclude using gist (
        tenant_id with =, weekday with =, int4range(starts_minute, ends_minute) with &&
    )
);

-- Services -------------------------------------------------------------------------------------
create table service (
    id               uuid primary key,
    tenant_id        uuid        not null references tenant (id),
    name             text        not null check (length(name) between 1 and 80),
    name_key         text        not null, -- lower case without accents, filled by the application
    category         text        not null check (length(category) between 1 and 40),
    duration_minutes integer     not null check (duration_minutes between 15 and 480 and duration_minutes % 15 = 0),
    price_cents      bigint      not null check (price_cents >= 0),
    price_type       text        not null check (price_type in ('FIXED', 'FROM')),
    active           boolean     not null default true,
    position         integer     not null default 0,
    created_at       timestamptz not null default now(),
    unique (tenant_id, name_key),
    unique (tenant_id, id) -- target of composite foreign keys
);

-- Professionals --------------------------------------------------------------------------------
create table professional (
    id           uuid primary key,
    tenant_id    uuid        not null references tenant (id),
    display_name text        not null check (length(display_name) between 1 and 60),
    user_id      uuid,
    color        text        not null check (color ~ '^#[0-9a-f]{6}$'),
    active       boolean     not null default true,
    own_hours    boolean     not null default false,
    created_at   timestamptz not null default now(),
    unique (tenant_id, id),
    unique (tenant_id, user_id),
    -- Only a member of the same salon can be linked; removing the member just unlinks.
    foreign key (tenant_id, user_id) references membership (tenant_id, user_id) on delete set null (user_id)
);

create table professional_service (
    tenant_id       uuid not null,
    professional_id uuid not null,
    service_id      uuid not null,
    primary key (professional_id, service_id),
    -- Composite keys: a link between two salons cannot be stored, whatever the application does.
    foreign key (tenant_id, professional_id) references professional (tenant_id, id),
    foreign key (tenant_id, service_id) references service (tenant_id, id)
);

create table professional_hours (
    tenant_id       uuid     not null,
    professional_id uuid     not null,
    weekday         smallint not null check (weekday between 1 and 7),
    starts_minute   integer  not null check (starts_minute between 0 and 1425 and starts_minute % 15 = 0),
    ends_minute     integer  not null check (ends_minute between 15 and 1440 and ends_minute % 15 = 0),
    check (starts_minute < ends_minute),
    foreign key (tenant_id, professional_id) references professional (tenant_id, id),
    constraint professional_hours_no_overlap exclude using gist (
        professional_id with =, weekday with =, int4range(starts_minute, ends_minute) with &&
    )
);

-- Row-Level Security on every tenant-owned table ------------------------------------------------
do $$
declare t text;
begin
    foreach t in array array['opening_hours', 'service', 'professional', 'professional_service', 'professional_hours'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy tenant_isolation on %I using (tenant_id = app_tenant_id()) with check (tenant_id = app_tenant_id())', t);
    end loop;
end $$;

grant select, insert, delete on opening_hours, professional_hours, professional_service to estilix_app;
grant select, insert, update on service, professional to estilix_app;
