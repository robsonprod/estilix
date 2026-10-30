-- Local demo data (profile `local` only; never in tests or production).
-- Subjects match the usernames you type on the fake login page (mock-oauth2-server).
-- Runs as estilix_owner; idempotent so it can be re-applied when this file changes.

insert into tenant (id, name, establishment_type, slug, plan_code) values
    ('11111111-1111-1111-1111-111111111111', 'Salão Bela Vista', 'SALON', 'salao-bela-vista', 'launch'),
    ('22222222-2222-2222-2222-222222222222', 'Barbearia do Zé', 'BARBERSHOP', 'barbearia-do-ze', 'launch')
on conflict (id) do nothing;

insert into app_user (id, subject, email, display_name, phone) values
    ('a0000000-0000-0000-0000-000000000001', 'dona-bela',    'bela@estilix.test',    'Bela Martins',   '+55 85 99876-0001'),
    ('a0000000-0000-0000-0000-000000000002', 'gerente-bela', 'gil@estilix.test',     'Gil Andrade',    null),
    ('a0000000-0000-0000-0000-000000000003', 'pro-bela',     'paula@estilix.test',   'Paula Rocha',    null),
    ('a0000000-0000-0000-0000-000000000004', 'dono-ze',      'ze@estilix.test',      'José Ferreira',  '+55 85 99876-0004'),
    ('a0000000-0000-0000-0000-000000000005', 'barbeiro-ze',  'caio@estilix.test',    'Caio Lima',      null)
on conflict (subject) do nothing;

insert into membership (tenant_id, user_id, role) values
    ('11111111-1111-1111-1111-111111111111', 'a0000000-0000-0000-0000-000000000001', 'OWNER'),
    ('11111111-1111-1111-1111-111111111111', 'a0000000-0000-0000-0000-000000000002', 'MANAGER'),
    ('11111111-1111-1111-1111-111111111111', 'a0000000-0000-0000-0000-000000000003', 'PROFESSIONAL'),
    ('22222222-2222-2222-2222-222222222222', 'a0000000-0000-0000-0000-000000000004', 'OWNER'),
    ('22222222-2222-2222-2222-222222222222', 'a0000000-0000-0000-0000-000000000005', 'PROFESSIONAL')
on conflict (tenant_id, user_id) do nothing;

-- Salon setup demo (change salon-setup) -------------------------------------------------------
update tenant set phone = '+55 85 3333-0001', address_city = 'Fortaleza', address_state = 'CE', time_zone = 'America/Fortaleza'
where id in ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222');

-- Opening hours: Bela Vista Tue–Sat with lunch break; Zé Mon–Sat 09:00–20:00.
insert into opening_hours (tenant_id, weekday, starts_minute, ends_minute)
select '11111111-1111-1111-1111-111111111111', d, s, e
from (values (2, 540, 720), (2, 780, 1140), (3, 540, 720), (3, 780, 1140), (4, 540, 720), (4, 780, 1140),
             (5, 540, 720), (5, 780, 1140), (6, 480, 1080)) as h(d, s, e)
where not exists (select 1 from opening_hours where tenant_id = '11111111-1111-1111-1111-111111111111');

insert into opening_hours (tenant_id, weekday, starts_minute, ends_minute)
select '22222222-2222-2222-2222-222222222222', d, 540, 1200 from generate_series(1, 6) d
where not exists (select 1 from opening_hours where tenant_id = '22222222-2222-2222-2222-222222222222');

insert into service (id, tenant_id, name, name_key, category, duration_minutes, price_cents, price_type, position) values
    ('5a000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Corte feminino', 'corte feminino', 'Cabelo', 60, 8000, 'FIXED', 0),
    ('5a000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'Escova', 'escova', 'Cabelo', 45, 5000, 'FIXED', 1),
    ('5a000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'Coloração', 'coloracao', 'Cabelo', 120, 15000, 'FROM', 2),
    ('5a000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'Manicure', 'manicure', 'Unhas', 45, 3500, 'FIXED', 3),
    ('5b000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'Corte masculino', 'corte masculino', 'Cabelo', 30, 4500, 'FIXED', 0),
    ('5b000000-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'Barba', 'barba', 'Barba', 30, 3500, 'FIXED', 1),
    ('5b000000-0000-0000-0000-000000000003', '22222222-2222-2222-2222-222222222222', 'Corte + barba', 'corte + barba', 'Combo', 60, 7000, 'FIXED', 2)
on conflict (id) do nothing;

insert into professional (id, tenant_id, display_name, user_id, color) values
    ('9a000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Paula', 'a0000000-0000-0000-0000-000000000003', '#8b2f5c'),
    ('9a000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'Rosa', null, '#2563eb'),
    ('9b000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'Caio', 'a0000000-0000-0000-0000-000000000005', '#059669'),
    ('9b000000-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'Zé', 'a0000000-0000-0000-0000-000000000004', '#d97706')
on conflict (id) do nothing;

insert into professional_service (tenant_id, professional_id, service_id) values
    ('11111111-1111-1111-1111-111111111111', '9a000000-0000-0000-0000-000000000001', '5a000000-0000-0000-0000-000000000001'),
    ('11111111-1111-1111-1111-111111111111', '9a000000-0000-0000-0000-000000000001', '5a000000-0000-0000-0000-000000000002'),
    ('11111111-1111-1111-1111-111111111111', '9a000000-0000-0000-0000-000000000001', '5a000000-0000-0000-0000-000000000003'),
    ('11111111-1111-1111-1111-111111111111', '9a000000-0000-0000-0000-000000000002', '5a000000-0000-0000-0000-000000000004'),
    ('22222222-2222-2222-2222-222222222222', '9b000000-0000-0000-0000-000000000001', '5b000000-0000-0000-0000-000000000001'),
    ('22222222-2222-2222-2222-222222222222', '9b000000-0000-0000-0000-000000000001', '5b000000-0000-0000-0000-000000000002'),
    ('22222222-2222-2222-2222-222222222222', '9b000000-0000-0000-0000-000000000002', '5b000000-0000-0000-0000-000000000003')
on conflict do nothing;

-- A salon waiting for approval, so the support queue has something to show (owner: "dona-nova").
insert into app_user (id, subject, email, display_name) values
    ('a0000000-0000-0000-0000-000000000006', 'dona-nova', 'lia@estilix.test', 'Lia Nogueira')
on conflict (subject) do nothing;

insert into tenant (id, name, establishment_type, status, slug, plan_code, phone, address_city, address_state, created_by,
                    contact_name, contact_email, terms_version, terms_accepted_by, terms_accepted_at, submitted_at) values
    ('33333333-3333-3333-3333-333333333333', 'Studio Lia Nails', 'SALON', 'PENDING_APPROVAL', 'studio-lia-nails', 'launch',
     '+55 85 98765-4321', 'Caucaia', 'CE', 'a0000000-0000-0000-0000-000000000006', 'Lia Nogueira', 'lia@estilix.test',
     'v0-draft', 'a0000000-0000-0000-0000-000000000006', now(), now())
on conflict (id) do nothing;

insert into membership (tenant_id, user_id, role) values
    ('33333333-3333-3333-3333-333333333333', 'a0000000-0000-0000-0000-000000000006', 'OWNER')
on conflict (tenant_id, user_id) do nothing;

-- Scheduling demo (change scheduling): bookings today and tomorrow, and a block -------------------
-- Times are relative to "today" in the salons' time zone (America/Fortaleza), so the agenda is never empty.
-- Codes of these demo groups are not usable (dummy hashes); new bookings through the page get real ones.
insert into booking_group (id, tenant_id, phone, code_hash, token_hash) values
    ('6a000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '+5585988881111', 'demo', 'demo-token-1'),
    ('6a000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '+5585988882222', 'demo', 'demo-token-2'),
    ('6b000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', '+5585988883333', 'demo', 'demo-token-3')
on conflict (id) do nothing;

insert into agenda_entry (id, tenant_id, kind, professional_id, service_id, group_id, starts_at, ends_at, customer_name, customer_phone, origin, block_reason)
select v.id::uuid, v.tenant::uuid, v.kind, v.pro::uuid, v.svc::uuid, v.grp::uuid,
       (date_trunc('day', now() at time zone 'America/Fortaleza') + v.day_offset * interval '1 day' + v.start_min * interval '1 minute') at time zone 'America/Fortaleza',
       (date_trunc('day', now() at time zone 'America/Fortaleza') + v.day_offset * interval '1 day' + v.end_min * interval '1 minute') at time zone 'America/Fortaleza',
       v.customer, v.phone, v.origin, v.reason
from (values
    ('7a000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'BOOKING', '9a000000-0000-0000-0000-000000000001', '5a000000-0000-0000-0000-000000000001', '6a000000-0000-0000-0000-000000000001', 0, 900, 960, 'Marina Costa', '+5585988881111', 'PUBLIC', null),
    ('7a000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'BOOKING', '9a000000-0000-0000-0000-000000000002', '5a000000-0000-0000-0000-000000000004', '6a000000-0000-0000-0000-000000000002', 1, 600, 645, 'Júlia Ramos', '+5585988882222', 'STAFF', null),
    ('7a000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'BLOCK', '9a000000-0000-0000-0000-000000000001', null, null, 1, 960, 1020, null, null, null, 'Curso'),
    ('7b000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'BOOKING', '9b000000-0000-0000-0000-000000000001', '5b000000-0000-0000-0000-000000000001', '6b000000-0000-0000-0000-000000000001', 0, 660, 690, 'Rafael Dias', '+5585988883333', 'PUBLIC', null)
) as v(id, tenant, kind, pro, svc, grp, day_offset, start_min, end_min, customer, phone, origin, reason)
where not exists (select 1 from agenda_entry e where e.id = v.id::uuid)
on conflict do nothing;

-- Customers and service records demo (change customers-and-service-records) -----------------------
-- Customers by phone; "on conflict do nothing" also covers a customer the V8 backfill already created.
insert into customer (id, tenant_id, phone, name, notes, marketing_consent, consent_changed_at) values
    ('8a000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '+5585988881111', 'Marina Costa', 'Prefere horários à tarde.', 'GRANTED', now() - interval '60 days'),
    ('8a000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '+5585988882222', 'Júlia Ramos', null, 'UNKNOWN', null),
    ('8a000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', '+5585988884444', 'Fernanda Lima', 'Alergia a amônia: usar coloração sem amônia.', 'REVOKED', now() - interval '20 days'),
    ('8a000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', '+5585988885555', 'Carla Mendes', null, 'UNKNOWN', null),
    ('8b000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', '+5585988883333', 'Rafael Dias', null, 'GRANTED', now() - interval '30 days'),
    ('8b000000-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', '+5585988886666', 'Pedro Souza', null, 'UNKNOWN', null)
on conflict do nothing;

insert into customer_consent_event (id, tenant_id, customer_id, consent, source, actor_user_id, occurred_at)
select v.id::uuid, v.tenant::uuid, c.id, v.consent, 'STAFF', v.actor::uuid, now() - v.days * interval '1 day'
from (values
    ('8c000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '+5585988881111', 'GRANTED', 'a0000000-0000-0000-0000-000000000001', 60),
    ('8c000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '+5585988884444', 'GRANTED', 'a0000000-0000-0000-0000-000000000002', 90),
    ('8c000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', '+5585988884444', 'REVOKED', 'a0000000-0000-0000-0000-000000000002', 20),
    ('8c000000-0000-0000-0000-000000000004', '22222222-2222-2222-2222-222222222222', '+5585988883333', 'GRANTED', 'a0000000-0000-0000-0000-000000000004', 30)
) as v(id, tenant, phone, consent, actor, days)
join customer c on c.tenant_id = v.tenant::uuid and c.phone = v.phone
on conflict do nothing;

-- Today: Carla did not come (09:00, the time is free again) and Fernanda is being served by Rosa (10:00).
insert into agenda_entry (id, tenant_id, kind, professional_id, service_id, group_id, starts_at, ends_at, customer_name, customer_phone, origin, status)
select v.id::uuid, v.tenant::uuid, 'BOOKING', v.pro::uuid, v.svc::uuid, null,
       (date_trunc('day', now() at time zone 'America/Fortaleza') + v.start_min * interval '1 minute') at time zone 'America/Fortaleza',
       (date_trunc('day', now() at time zone 'America/Fortaleza') + v.end_min * interval '1 minute') at time zone 'America/Fortaleza',
       v.customer, v.phone, 'WALK_IN', v.status
from (values
    ('7a000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', '9a000000-0000-0000-0000-000000000001', '5a000000-0000-0000-0000-000000000002', 540, 585, 'Carla Mendes', '+5585988885555', 'NO_SHOW'),
    ('7a000000-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', '9a000000-0000-0000-0000-000000000002', '5a000000-0000-0000-0000-000000000004', 600, 645, 'Fernanda Lima', '+5585988884444', 'IN_SERVICE')
) as v(id, tenant, pro, svc, start_min, end_min, customer, phone, status)
where not exists (select 1 from agenda_entry e where e.id = v.id::uuid)
on conflict do nothing;

-- Every demo booking points to its customer.
update agenda_entry e set customer_id = c.id
from customer c
where e.kind = 'BOOKING' and e.customer_id is null and c.tenant_id = e.tenant_id and c.phone = e.customer_phone;

-- Past visits, so "costuma voltar a cada N dias" shows real numbers (closed at 15:00 local, N days ago).
insert into service_record (id, tenant_id, customer_id, status, payment_method, total_cents, opened_at, closed_at)
select v.id::uuid, v.tenant::uuid, c.id, 'CLOSED', v.pay, v.cents,
       (date_trunc('day', now() at time zone 'America/Fortaleza') - v.days * interval '1 day' + interval '14 hours') at time zone 'America/Fortaleza',
       (date_trunc('day', now() at time zone 'America/Fortaleza') - v.days * interval '1 day' + interval '15 hours') at time zone 'America/Fortaleza'
from (values
    ('8d000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '+5585988881111', 95, 'PIX', 8000),
    ('8d000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '+5585988881111', 63, 'CREDIT', 11500),
    ('8d000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', '+5585988881111', 33, 'PIX', 8000),
    ('8d000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', '+5585988884444', 70, 'DEBIT', 18000),
    ('8d000000-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', '+5585988884444', 21, 'CASH', 3500),
    ('8d000000-0000-0000-0000-000000000006', '22222222-2222-2222-2222-222222222222', '+5585988883333', 42, 'PIX', 4500),
    ('8d000000-0000-0000-0000-000000000007', '22222222-2222-2222-2222-222222222222', '+5585988883333', 28, 'PIX', 4500),
    ('8d000000-0000-0000-0000-000000000008', '22222222-2222-2222-2222-222222222222', '+5585988883333', 14, 'CASH', 4500)
) as v(id, tenant, phone, days, pay, cents)
join customer c on c.tenant_id = v.tenant::uuid and c.phone = v.phone
on conflict do nothing;

-- Fernanda's open comanda for today's manicure.
insert into service_record (id, tenant_id, customer_id, status, total_cents, opened_at)
select '8d000000-0000-0000-0000-000000000009', '11111111-1111-1111-1111-111111111111', c.id, 'OPEN', 3500, now()
from customer c
where c.tenant_id = '11111111-1111-1111-1111-111111111111' and c.phone = '+5585988884444'
  and not exists (select 1 from service_record r where r.customer_id = c.id and r.status = 'OPEN')
  -- only when today's booking could be placed (an older local database may already use that time)
  and exists (select 1 from agenda_entry e where e.id = '7a000000-0000-0000-0000-000000000005' and e.status = 'IN_SERVICE')
on conflict do nothing;

insert into service_record_item (id, tenant_id, record_id, service_id, professional_id, price_cents, agenda_entry_id)
select v.id::uuid, v.tenant::uuid, v.rec::uuid, v.svc::uuid, v.pro::uuid, v.cents, v.entry::uuid
from (values
    ('8e000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '8d000000-0000-0000-0000-000000000001', '5a000000-0000-0000-0000-000000000001', '9a000000-0000-0000-0000-000000000001', 8000, null),
    ('8e000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '8d000000-0000-0000-0000-000000000002', '5a000000-0000-0000-0000-000000000001', '9a000000-0000-0000-0000-000000000001', 8000, null),
    ('8e000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', '8d000000-0000-0000-0000-000000000002', '5a000000-0000-0000-0000-000000000004', '9a000000-0000-0000-0000-000000000002', 3500, null),
    ('8e000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', '8d000000-0000-0000-0000-000000000003', '5a000000-0000-0000-0000-000000000001', '9a000000-0000-0000-0000-000000000001', 8000, null),
    ('8e000000-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', '8d000000-0000-0000-0000-000000000004', '5a000000-0000-0000-0000-000000000003', '9a000000-0000-0000-0000-000000000001', 18000, null),
    ('8e000000-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111', '8d000000-0000-0000-0000-000000000005', '5a000000-0000-0000-0000-000000000004', '9a000000-0000-0000-0000-000000000002', 3500, null),
    ('8e000000-0000-0000-0000-000000000007', '22222222-2222-2222-2222-222222222222', '8d000000-0000-0000-0000-000000000006', '5b000000-0000-0000-0000-000000000001', '9b000000-0000-0000-0000-000000000001', 4500, null),
    ('8e000000-0000-0000-0000-000000000008', '22222222-2222-2222-2222-222222222222', '8d000000-0000-0000-0000-000000000007', '5b000000-0000-0000-0000-000000000001', '9b000000-0000-0000-0000-000000000001', 4500, null),
    ('8e000000-0000-0000-0000-000000000009', '22222222-2222-2222-2222-222222222222', '8d000000-0000-0000-0000-000000000008', '5b000000-0000-0000-0000-000000000001', '9b000000-0000-0000-0000-000000000001', 4500, null),
    ('8e000000-0000-0000-0000-000000000010', '11111111-1111-1111-1111-111111111111', '8d000000-0000-0000-0000-000000000009', '5a000000-0000-0000-0000-000000000004', '9a000000-0000-0000-0000-000000000002', 3500, '7a000000-0000-0000-0000-000000000005')
) as v(id, tenant, rec, svc, pro, cents, entry)
where exists (select 1 from service_record r where r.id = v.rec::uuid)
  and (v.entry is null or exists (select 1 from agenda_entry e where e.id = v.entry::uuid))
on conflict do nothing;
