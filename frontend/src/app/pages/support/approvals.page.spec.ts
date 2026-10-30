import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { PendingSalon } from '../../core/models';
import { ApprovalsPage } from './approvals.page';

/** Task 7.4: support approves in two taps and rejects with a reason. */
describe('ApprovalsPage', () => {
  const salon: PendingSalon = {
    tenantId: 't1', name: 'Barbearia do Zé', establishmentType: 'BARBERSHOP', city: 'Fortaleza', state: 'CE',
    phone: '+55 85 98888-7777', contactName: 'José Ferreira', contactEmail: 'ze@example.com', planCode: 'launch',
    termsVersion: 'v0-draft', termsAcceptedAt: null, submittedAt: '2026-10-29T15:00:00Z',
  };
  let api: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(() => {
    api = {
      approvals: vi.fn().mockResolvedValueOnce([salon]).mockResolvedValue([]),
      approve: vi.fn().mockResolvedValue(undefined),
      reject: vi.fn().mockResolvedValue(undefined),
    };
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ApiService, useValue: api }] });
  });

  async function render() {
    const fixture = TestBed.createComponent(ApprovalsPage);
    const settle = async () => {
      for (let i = 0; i < 4; i++) {
        await new Promise((r) => setTimeout(r, 0));
        fixture.detectChanges();
      }
    };
    fixture.detectChanges();
    await settle();
    return { fixture, settle, el: fixture.nativeElement as HTMLElement };
  }

  it('lists the salon with its contact for verification', async () => {
    const { el } = await render();

    expect(el.textContent).toContain('Barbearia do Zé');
    expect(el.textContent).toContain('José Ferreira');
    expect(el.textContent).toContain('+55 85 98888-7777');
  });

  it('approves with one tap and empties the queue', async () => {
    const { el, settle } = await render();

    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Aprovar') as HTMLButtonElement).click();
    await settle();

    expect(api['approve']).toHaveBeenCalledWith('t1');
    expect(el.textContent).toContain('Barbearia do Zé foi liberado');
  });

  it('rejects with the typed reason', async () => {
    const { fixture, el, settle } = await render();
    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Recusar…')) as HTMLButtonElement).click();
    await settle();
    (fixture.componentInstance as unknown as { reason: string }).reason = 'Telefone não atende';

    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Recusar') as HTMLButtonElement).click();
    await settle();

    expect(api['reject']).toHaveBeenCalledWith('t1', 'Telefone não atende');
  });
});
