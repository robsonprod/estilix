-- Self-service onboarding, support approval and the plan catalog (change salon-setup, design D1/D2/D5).

-- Plans ----------------------------------------------------------------------------------------
-- Platform table (no tenant): public catalog shown on the plans page.
create table plan (
    code        text primary key,
    name        text    not null,
    description text    not null,
    price_cents bigint  not null check (price_cents >= 0),
    features    jsonb   not null default '[]'::jsonb,
    active      boolean not null default true,
    position    integer not null default 0
);

insert into plan (code, name, description, price_cents, features, position) values
    ('launch', 'Lançamento', 'Tudo liberado e sem custo durante o lançamento do Estilix.', 0,
     '["Equipe ilimitada com papéis", "Serviços, profissionais e horários", "Dados protegidos (LGPD)", "Agenda online sem ligação (em breve)", "Atendimentos em 3 toques (em breve)", "Caixa e comissões (em breve)", "Clientes para chamar (em breve)"]', 1);

grant select on plan to estilix_app;

-- Tenant: profile, application and decision columns --------------------------------------------
alter table tenant
    add column slug                text,
    add column phone               text,
    add column address_street      text,
    add column address_number      text,
    add column address_district    text,
    add column address_city        text,
    add column address_state       text check (address_state is null or address_state ~ '^[A-Z]{2}$'),
    add column address_postal_code text,
    add column plan_code           text references plan (code),
    add column terms_version       text,
    add column terms_accepted_by   uuid,
    add column terms_accepted_at   timestamptz,
    add column created_by          uuid,
    add column contact_name        text, -- snapshot for verification; cleared on approval (data minimization)
    add column contact_email       text,
    add column submitted_at        timestamptz,
    add column decided_by          uuid,
    add column decided_at          timestamptz,
    add column rejection_reason    text;

-- Existing tenants (local seed) get a slug from their name: lower case, no accents, hyphens.
update tenant set
    slug = trim(both '-' from regexp_replace(
               translate(lower(name), 'áàâãäéèêëíìîïóòôõöúùûüçñ', 'aaaaaeeeeiiiiooooouuuucn'),
               '[^a-z0-9]+', '-', 'g')),
    plan_code = 'launch';

alter table tenant
    alter column slug set not null,
    add constraint tenant_slug_format check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' and length(slug) between 3 and 60),
    add constraint tenant_slug_unique unique (slug);

alter table tenant drop constraint tenant_status_check;
alter table tenant add constraint tenant_status_check
    check (status in ('PENDING_APPROVAL', 'REJECTED', 'ACTIVE', 'SUSPENDED'));

-- One salon waiting for approval (or to be fixed) per person at a time.
create unique index tenant_one_open_application on tenant (created_by)
    where status in ('PENDING_APPROVAL', 'REJECTED');

-- Lifecycle state machine enforced by the database (design D1) ---------------------------------
--   PENDING_APPROVAL -> ACTIVE | REJECTED     platform support only
--   REJECTED -> PENDING_APPROVAL              the person who created the salon (resubmission)
--   ACTIVE <-> SUSPENDED                      platform support only
-- The rules bind the application role; migrations and database operators are trusted, as with RLS.
create function tenant_status_transition() returns trigger language plpgsql as $$
begin
    if new.status is distinct from old.status and current_user = 'estilix_app' then
        if not (
            (old.status = 'PENDING_APPROVAL' and new.status in ('ACTIVE', 'REJECTED') and app_is_support())
            or (old.status = 'REJECTED' and new.status = 'PENDING_APPROVAL' and old.created_by = app_user_id())
            or (old.status = 'ACTIVE' and new.status = 'SUSPENDED' and app_is_support())
            or (old.status = 'SUSPENDED' and new.status = 'ACTIVE' and app_is_support())
        ) then
            raise exception 'tenant status % -> % not allowed', old.status, new.status using errcode = '42501';
        end if;
    end if;
    return new;
end $$;
create trigger tenant_status_transition before update on tenant
    for each row execute function tenant_status_transition();

-- Policies: the application can only create a PENDING salon, as its creator, inside the new
-- salon's own context; updates stay inside the current salon.
create policy tenant_insert on tenant for insert with check (
    id = app_tenant_id() and status = 'PENDING_APPROVAL' and created_by = app_user_id()
);
create policy tenant_update on tenant for update
    using (id = app_tenant_id()) with check (id = app_tenant_id());

grant insert on tenant to estilix_app;
grant update (name, establishment_type, time_zone, slug, phone, address_street, address_number, address_district,
              address_city, address_state, address_postal_code, status, submitted_at, decided_by, decided_at,
              rejection_reason, contact_name, contact_email)
    on tenant to estilix_app;

-- Slug availability for the public endpoint: answers yes/no without exposing the owning salon.
create function slug_available(p_slug text) returns boolean
    language sql stable security definer set search_path = public
as $$ select not exists (select 1 from tenant where slug = p_slug) $$;
revoke all on function slug_available(text) from public;
grant execute on function slug_available(text) to estilix_app;
