-- Customers and service records (change customers-and-service-records, design D3, D6, D7, D8, D9, D13).

-- Customers ------------------------------------------------------------------------------------
create table customer (
    id                 uuid primary key,
    tenant_id          uuid        not null references tenant (id),
    phone              text,       -- E.164, personal data; null only after anonymisation
    name               text        not null check (length(name) between 2 and 80),
    -- Lowercase name without Portuguese accents, for "start of any word" search (no unaccent extension needed).
    search_key         text        generated always as (
        translate(lower(name), 'áàâãäéèêëíìîïóòôõöúùûüçñ', 'aaaaaeeeeiiiiooooouuuucn')
    ) stored,
    notes              text check (notes is null or length(notes) <= 1000),
    marketing_consent  text        not null default 'UNKNOWN' check (marketing_consent in ('UNKNOWN', 'GRANTED', 'REVOKED')),
    consent_changed_at timestamptz,
    anonymised_at      timestamptz,
    created_at         timestamptz not null default now(),
    unique (tenant_id, id),
    check ((anonymised_at is null) = (phone is not null))
);
-- One customer per phone per salon; anonymised customers release the phone.
create unique index customer_phone on customer (tenant_id, phone) where phone is not null;

create table customer_consent_event (
    id            uuid primary key,
    tenant_id     uuid        not null references tenant (id),
    customer_id   uuid        not null,
    consent       text        not null check (consent in ('GRANTED', 'REVOKED')),
    source        text        not null check (source in ('STAFF', 'CUSTOMER')),
    actor_user_id uuid,
    occurred_at   timestamptz not null default now(),
    foreign key (tenant_id, customer_id) references customer (tenant_id, id)
);
create index customer_consent_event_customer on customer_consent_event (tenant_id, customer_id, occurred_at);

-- Backfill: one customer per phone, named after the most recent booking (design D6).
insert into customer (id, tenant_id, phone, name)
select gen_random_uuid(), tenant_id, customer_phone, (array_agg(customer_name order by created_at desc))[1]
from agenda_entry
where kind = 'BOOKING' and customer_phone is not null
group by tenant_id, customer_phone;

-- Agenda: customer link, attendance statuses and walk-ins ---------------------------------------
alter table agenda_entry
    add column customer_id uuid,
    add constraint agenda_entry_customer_fk foreign key (tenant_id, customer_id) references customer (tenant_id, id),
    add constraint agenda_entry_tenant_id_key unique (tenant_id, id);

update agenda_entry e set customer_id = c.id
from customer c
where e.kind = 'BOOKING' and c.tenant_id = e.tenant_id and c.phone = e.customer_phone;

create index agenda_entry_customer on agenda_entry (tenant_id, customer_id);

alter table agenda_entry drop constraint agenda_entry_status_check;
alter table agenda_entry add constraint agenda_entry_status_check
    check (status in ('CONFIRMED', 'IN_SERVICE', 'DONE', 'NO_SHOW', 'CANCELLED'));

alter table agenda_entry drop constraint agenda_entry_origin_check;
alter table agenda_entry add constraint agenda_entry_origin_check check (origin in ('PUBLIC', 'STAFF', 'WALK_IN'));

-- The phone may be erased by anonymisation; walk-ins have no booking group (no code, no link).
alter table agenda_entry drop constraint agenda_entry_check1;
alter table agenda_entry add constraint agenda_entry_kind_fields check (
    (kind = 'BOOKING' and service_id is not null and (group_id is not null or origin = 'WALK_IN') and customer_name is not null
        and origin is not null and block_reason is null)
    or
    (kind = 'BLOCK' and service_id is null and group_id is null and customer_name is null and customer_id is null
        and customer_phone is null and origin is null and status = 'CONFIRMED')
);

-- Time is held by entries that are confirmed, in service or done; no-shows and cancellations free it (D3).
alter table agenda_entry drop constraint agenda_entry_no_overlap;
alter table agenda_entry add constraint agenda_entry_no_overlap exclude using gist (
    professional_id with =,
    tstzrange(starts_at, ends_at, '[)') with &&
) where (status in ('CONFIRMED', 'IN_SERVICE', 'DONE'));

alter table booking_group alter column phone drop not null;
-- Anonymisation erases the phone and rotates the secrets so old links and codes stop working (D10).
grant update (phone, code_hash) on booking_group to estilix_app;

-- Service records -------------------------------------------------------------------------------
create table service_record (
    id             uuid primary key,
    tenant_id      uuid        not null references tenant (id),
    customer_id    uuid        not null,
    status         text        not null default 'OPEN' check (status in ('OPEN', 'CLOSED', 'VOIDED')),
    payment_method text check (payment_method in ('PIX', 'CASH', 'DEBIT', 'CREDIT')),
    total_cents    bigint      not null default 0 check (total_cents >= 0),
    opened_at      timestamptz not null default now(),
    opened_by      uuid,
    closed_at      timestamptz,
    void_reason    text check (void_reason is null or length(void_reason) between 5 and 200),
    voided_at      timestamptz,
    voided_by      uuid,
    unique (tenant_id, id),
    foreign key (tenant_id, customer_id) references customer (tenant_id, id),
    check (status <> 'OPEN' or (closed_at is null and payment_method is null and void_reason is null)),
    check (status <> 'CLOSED' or (payment_method is not null and closed_at is not null and void_reason is null)),
    check (status <> 'VOIDED' or (void_reason is not null and voided_at is not null))
);
-- At most one open record per customer (D9).
create unique index service_record_one_open on service_record (tenant_id, customer_id) where status = 'OPEN';
create index service_record_customer on service_record (tenant_id, customer_id, status);
create index service_record_opened on service_record (tenant_id, opened_at);

create table service_record_item (
    id              uuid primary key,
    tenant_id       uuid        not null references tenant (id),
    record_id       uuid        not null,
    service_id      uuid        not null,
    professional_id uuid        not null,
    price_cents     bigint      not null check (price_cents between 0 and 1000000),
    agenda_entry_id uuid unique,
    -- clock_timestamp(): items added in one transaction keep their order.
    created_at      timestamptz not null default clock_timestamp(),
    foreign key (tenant_id, record_id) references service_record (tenant_id, id),
    foreign key (tenant_id, service_id) references service (tenant_id, id),
    foreign key (tenant_id, professional_id) references professional (tenant_id, id),
    foreign key (tenant_id, agenda_entry_id) references agenda_entry (tenant_id, id)
);
create index service_record_item_record on service_record_item (record_id);
create index service_record_item_professional on service_record_item (tenant_id, professional_id);

do $$
declare t text;
begin
    foreach t in array array['customer', 'customer_consent_event', 'service_record', 'service_record_item'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy tenant_isolation on %I using (tenant_id = app_tenant_id()) with check (tenant_id = app_tenant_id())', t);
    end loop;
end $$;

grant select, insert, update on customer to estilix_app;
grant select, insert on customer_consent_event to estilix_app;
grant select, insert, update on service_record to estilix_app;
grant select, insert, update, delete on service_record_item to estilix_app;

-- Read models (security_invoker: the caller's RLS applies, so they cannot widen reads) -----------

-- Which professional has dealt with which customer: the PROFESSIONAL scope (D7).
create view customer_professional with (security_invoker = true) as
select tenant_id, customer_id, professional_id
from agenda_entry
where kind = 'BOOKING' and customer_id is not null and status <> 'CANCELLED'
union
select i.tenant_id, r.customer_id, i.professional_id
from service_record_item i join service_record r on r.id = i.record_id;

-- History computed on read: no counters to correct on void or anonymisation (D8).
create view customer_visit_stats with (security_invoker = true) as
select c.tenant_id,
       c.id                                                                  as customer_id,
       count(r.id) filter (where r.status = 'CLOSED')                        as visits,
       coalesce(sum(r.total_cents) filter (where r.status = 'CLOSED'), 0)    as total_cents,
       max(r.closed_at) filter (where r.status = 'CLOSED')                   as last_visit,
       (select count(*) from agenda_entry e where e.tenant_id = c.tenant_id and e.customer_id = c.id and e.status = 'NO_SHOW') as no_shows
from customer c
left join service_record r on r.tenant_id = c.tenant_id and r.customer_id = c.id
group by c.tenant_id, c.id;

grant select on customer_professional, customer_visit_stats to estilix_app;

-- The 3-tap service record shipped with this change.
update plan set features =
    '["Agenda online sem ligação", "Agenda da equipe com bloqueios", "Atendimento em 3 toques", "Clientes com histórico", "Equipe ilimitada com papéis", "Serviços, profissionais e horários", "Dados protegidos (LGPD)", "Caixa e comissões (em breve)", "Clientes para chamar (em breve)"]'
where code = 'launch';
