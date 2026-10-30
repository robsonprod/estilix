import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { Application } from '../../core/models';
import { StatusPage } from './status.page';

/** Task 7.2: status screens for a salon in analysis or rejected. */
describe('StatusPage', () => {
  const base: Application = {
    tenantId: 't1', name: 'Barbearia do Zé', establishmentType: 'BARBERSHOP', status: 'PENDING_APPROVAL', slug: 'barbearia-do-ze',
    phone: '+55 85 98888-7777', address: { street: null, number: null, district: null, city: 'Fortaleza', state: 'CE', postalCode: null },
    timeZone: 'America/Fortaleza', contactName: 'José', submittedAt: '2026-10-29T15:00:00Z', rejectionReason: null,
    hours: [{ weekday: 1, start: '09:00', end: '19:00' }], services: [],
  };

  async function render(app: Application | null) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: { application: vi.fn().mockResolvedValue(app) } },
        { provide: OidcSecurityService, useValue: {} },
      ],
    });
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(StatusPage);
    fixture.detectChanges();
    for (let i = 0; i < 3; i++) {
      await new Promise((r) => setTimeout(r, 0));
      fixture.detectChanges();
    }
    return { el: fixture.nativeElement as HTMLElement, navigate };
  }

  it('says the salon is in analysis and shows the summary', async () => {
    const { el } = await render(base);

    expect(el.textContent).toContain('Seu salão está em análise');
    expect(el.textContent).toContain('Barbearia do Zé');
    expect(el.textContent).toContain('Segunda: 09:00–19:00');
  });

  it('shows the rejection reason and the fix action', async () => {
    const { el } = await render({ ...base, status: 'REJECTED', rejectionReason: 'Telefone não atende' });

    expect(el.textContent).toContain('Telefone não atende');
    expect(el.textContent).toContain('Corrigir e reenviar');
  });

  it('goes back to the landing logic when there is nothing pending (e.g. approved)', async () => {
    const { navigate } = await render(null);

    expect(navigate).toHaveBeenCalledWith(['/entrar']);
  });
});
