import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { BreakGlassSession, Me, Member } from '../../core/models';
import { SessionService } from '../../core/session.service';
import { breakGlassFormError, SupportTenantPage } from './support-tenant.page';

describe('breakGlassFormError (task 8.5)', () => {
  it('requires a reason of at least 20 characters', () => {
    expect(breakGlassFormError('erro na tela', 15)).toContain('20 caracteres');
  });

  it('limits the duration to 60 minutes', () => {
    expect(breakGlassFormError('Ticket #4821, erro ao listar a equipe', 90)).toContain('60 minutos');
  });

  it('accepts a valid request', () => {
    expect(breakGlassFormError('Ticket #4821, erro ao listar a equipe', 30)).toBeNull();
  });
});

describe('SupportTenantPage (task 8.5)', () => {
  const masked: Member[] = [{ userId: 'u1', name: 'A*** P**** S****', email: 'a***@example.com', role: 'OWNER', since: '' }];
  const active: BreakGlassSession = {
    id: 'bg1', supportUserId: 'support-user', reason: 'Ticket #4821, erro ao listar a equipe',
    startedAt: '2026-10-29T15:00:00Z', expiresAt: '2026-10-29T15:30:00Z', endedAt: null, status: 'ACTIVE', unmaskedReads: 0,
  };
  const me: Me = { userId: 'support-user', name: null, email: null, phone: null, platformSupport: true, tenants: [] };
  let api: { members: ReturnType<typeof vi.fn>; accessHistory: ReturnType<typeof vi.fn>; openBreakGlass: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    api = {
      members: vi.fn().mockResolvedValue(masked),
      accessHistory: vi.fn().mockResolvedValue([]),
      openBreakGlass: vi.fn().mockResolvedValue(active),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: SessionService, useValue: { ensureLoaded: () => Promise.resolve(me) } },
      ],
    });
  });

  /** The page chains several awaits; let them all settle before asserting. */
  async function settle(fixture: { detectChanges(): void; whenStable(): Promise<unknown> }) {
    for (let i = 0; i < 5; i++) {
      await new Promise((r) => setTimeout(r, 0));
      await fixture.whenStable();
      fixture.detectChanges();
    }
  }

  async function render() {
    const fixture = TestBed.createComponent(SupportTenantPage);
    fixture.componentRef.setInput('tenantId', 't1');
    fixture.detectChanges();
    await settle(fixture);
    return fixture;
  }

  it('shows members masked and the break-glass form when no session is active', async () => {
    const el = (await render()).nativeElement as HTMLElement;

    expect(el.textContent).toContain('A*** P**** S****');
    expect(el.textContent).toContain('dados mascarados');
    expect(el.querySelector('textarea')).not.toBeNull();
  });

  it('does not call the API when the reason is too short', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await settle(fixture);

    expect(api.openBreakGlass).not.toHaveBeenCalled();
    expect(el.textContent).toContain('pelo menos 20 caracteres');
  });

  it('shows the active-session warning when this support user already has a session', async () => {
    api.accessHistory.mockResolvedValue([active]);

    const el = (await render()).nativeElement as HTMLElement;

    expect(el.textContent).toContain('Acesso emergencial ativo');
    expect(el.querySelector('textarea')).toBeNull();
  });
});
