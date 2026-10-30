-- Runs once as the PostgreSQL superuser when the database is created (Docker init / Testcontainers).
-- Two roles on purpose (design D4):
--   estilix_owner  owns the schema and runs Flyway migrations only. BYPASSRLS because data
--                  migrations and the local seed work across salons; it is never used at runtime.
--   estilix_app    runtime role: not the owner, NOBYPASSRLS, so Row-Level Security always applies.
-- Passwords here are for local development and tests only; AWS uses Secrets Manager.
create role estilix_owner login bypassrls password 'estilix_owner';
create role estilix_app login nobypassrls password 'estilix_app';

grant create, usage on schema public to estilix_owner;
grant usage on schema public to estilix_app;

-- Extensions need superuser; created here so migrations can rely on them.
create extension if not exists btree_gist; -- exclusion constraints (no overlapping hours)
