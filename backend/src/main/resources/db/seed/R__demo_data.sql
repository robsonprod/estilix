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
