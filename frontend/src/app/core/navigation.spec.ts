import { describe, expect, it } from 'vitest';
import { Me, MyTenant, Permission, TenantStatus } from './models';
import { bottomBarFor, landingFor, menuFor } from './navigation';

const tenant = (id: string, status: TenantStatus = 'ACTIVE', role: MyTenant['role'] = 'OWNER'): MyTenant => ({
  id,
  name: `Salão ${id}`,
  establishmentType: 'SALON',
  status,
  role,
  permissions: [],
  supportAccess: null,
  professional: null,
});

const me = (tenants: MyTenant[], platformSupport = false): Me => ({
  userId: 'u1',
  name: 'Bela',
  email: 'bela@example.com',
  phone: null,
  platformSupport,
  tenants,
});

describe('landingFor', () => {
  it('sends a single-salon user straight to the salon, without a picker', () => {
    expect(landingFor(me([tenant('a')]), null)).toEqual(['/s', 'a']);
  });

  it('shows the picker once to a user with several active salons', () => {
    expect(landingFor(me([tenant('a'), tenant('b')]), null)).toEqual(['/escolher-salao']);
  });

  it('remembers the last salon chosen on this device', () => {
    expect(landingFor(me([tenant('a'), tenant('b')]), 'b')).toEqual(['/s', 'b']);
  });

  it('ignores a remembered salon the user no longer belongs to', () => {
    expect(landingFor(me([tenant('a'), tenant('b')]), 'gone')).toEqual(['/escolher-salao']);
  });

  it('sends the owner of a salon in analysis or rejected to the status screen', () => {
    expect(landingFor(me([tenant('a', 'PENDING_APPROVAL')]), null)).toEqual(['/cadastro', 'status']);
    expect(landingFor(me([tenant('a', 'REJECTED')]), null)).toEqual(['/cadastro', 'status']);
  });

  it('an active salon wins over another one in analysis', () => {
    expect(landingFor(me([tenant('a'), tenant('b', 'PENDING_APPROVAL')]), null)).toEqual(['/s', 'a']);
  });

  it('continues the subscription after sign-in', () => {
    expect(landingFor(me([]), null, 'launch')).toEqual(['/cadastro']);
  });

  it('explains what to do when the user has no salon', () => {
    expect(landingFor(me([]), null)).toEqual(['/sem-salao']);
  });

  it('sends platform support to the support area', () => {
    expect(landingFor(me([], true), null)).toEqual(['/suporte']);
  });
});

describe('menuFor', () => {
  const labels = (p: Permission[]) => menuFor(p).map((i) => i.label);
  const owner: Permission[] = [
    'VIEW_MEMBERS', 'MANAGE_MEMBERS', 'VIEW_AUDIT', 'VIEW_ACCESS_HISTORY', 'VIEW_SALON', 'MANAGE_SALON',
    'VIEW_SERVICES', 'MANAGE_SERVICES', 'VIEW_PROFESSIONALS', 'MANAGE_PROFESSIONALS', 'VIEW_AGENDA', 'MANAGE_AGENDA', 'MANAGE_BOOKING_RULES',
    'VIEW_CUSTOMERS', 'MANAGE_CUSTOMERS', 'MANAGE_MARKETING_CONSENT', 'CUSTOMER_DATA_RIGHTS', 'VIEW_SERVICE_RECORDS', 'MANAGE_SERVICE_RECORDS', 'VOID_SERVICE_RECORDS',
  ];

  it('owner sees everything', () => {
    expect(labels(owner)).toEqual(['Início', 'Agenda', 'Clientes', 'Serviços', 'Profissionais', 'Salão', 'Equipe', 'Acessos', 'Perfil']);
  });

  it('manager sees the catalog and the team but not the access history', () => {
    expect(labels(['VIEW_MEMBERS', 'VIEW_SALON', 'VIEW_SERVICES', 'MANAGE_SERVICES', 'VIEW_PROFESSIONALS', 'MANAGE_PROFESSIONALS', 'VIEW_AGENDA', 'MANAGE_AGENDA']))
      .toEqual(['Início', 'Agenda', 'Serviços', 'Profissionais', 'Salão', 'Equipe', 'Perfil']);
  });

  it('professional sees neither team management nor the audit log', () => {
    expect(labels(['VIEW_SALON', 'VIEW_SERVICES', 'VIEW_PROFESSIONALS', 'VIEW_AGENDA', 'MANAGE_AGENDA', 'VIEW_CUSTOMERS']))
      .toEqual(['Início', 'Agenda', 'Clientes', 'Serviços', 'Profissionais', 'Salão', 'Perfil']);
  });

  it('the phone bottom bar keeps at most the primary items plus "Mais"', () => {
    const bar = bottomBarFor(menuFor(owner)).map((i) => i.label);
    expect(bar).toEqual(['Início', 'Agenda', 'Clientes', 'Mais']);
  });
});
