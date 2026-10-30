import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { CustomersApi } from '../../core/customers-api.service';
import { CustomerDetail } from '../../core/models';
import { SessionService } from '../../core/session.service';
import { lastVisitLabel, usualIntervalLabel } from './customer-labels';
import { CustomerPage } from './customer.page';

/** Task 5.4: customer detail, consent and privacy controls by role. */
describe('customer labels', () => {
  const now = new Date(2026, 10, 3, 15, 0);

  it('says when the last visit was', () => {
    expect(lastVisitLabel(null, now)).toBe('Ainda sem visitas');
    expect(lastVisitLabel(new Date(2026, 10, 3, 9, 0).toISOString(), now)).toBe('Última visita hoje');
    expect(lastVisitLabel(new Date(2026, 10, 2, 20, 0).toISOString(), now)).toBe('Última visita ontem');
    expect(lastVisitLabel(new Date(2026, 9, 22, 10, 0).toISOString(), now)).toBe('Última visita há 12 dias');
  });

  it('shows the usual interval only when there is one', () => {
    expect(usualIntervalLabel(32)).toBe('Costuma voltar a cada 32 dias');
    expect(usualIntervalLabel(null)).toBeNull();
  });
});

describe('CustomerPage', () => {
  const ana: CustomerDetail = {
    id: 'c1', name: 'Ana Souza', phone: '+5511988887777', notes: 'Alergia a amônia', marketingConsent: 'UNKNOWN', consentChangedAt: null,
    consentHistory: [], visits: 3, noShows: 1, totalSpent: '175.00', lastVisit: new Date().toISOString(), usualIntervalDays: 32,
    history: [{ recordId: 'r1', closedAt: new Date().toISOString(), total: '45.00', paymentMethod: 'PIX', items: [{ serviceName: 'Corte', professionalName: 'Paula', price: '45.00' }] }],
    anonymised: false,
  };

  async function render(permissions: string[]) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: CustomersApi, useValue: { customer: vi.fn().mockResolvedValue(ana) } },
        { provide: SessionService, useValue: { currentTenantId: signal('t1'), permissions: signal(permissions) } },
      ],
    });
    const fixture = TestBed.createComponent(CustomerPage);
    fixture.componentRef.setInput('customerId', 'c1');
    fixture.detectChanges();
    for (let i = 0; i < 4; i++) {
      await new Promise((r) => setTimeout(r, 0));
      fixture.detectChanges();
    }
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the numbers that matter on top', async () => {
    const el = await render(['VIEW_CUSTOMERS']);

    expect(el.textContent).toContain('Ana Souza');
    expect(el.textContent).toContain('R$ 175,00');
    expect(el.textContent).toContain('Costuma voltar a cada 32 dias');
    expect(el.textContent).toContain('Corte com Paula');
  });

  it('the owner sees consent buttons and the privacy section', async () => {
    const el = await render(['VIEW_CUSTOMERS', 'MANAGE_CUSTOMERS', 'MANAGE_MARKETING_CONSENT', 'CUSTOMER_DATA_RIGHTS']);

    expect(el.textContent).toContain('Aceita receber');
    expect(el.textContent).toContain('Privacidade (LGPD)');
  });

  it('a professional neither changes consent nor sees the privacy section', async () => {
    const el = await render(['VIEW_CUSTOMERS', 'MANAGE_CUSTOMERS']);

    expect(el.textContent).not.toContain('Aceita receber');
    expect(el.textContent).not.toContain('Privacidade (LGPD)');
    expect(el.textContent).toContain('Editar dados');
  });

  it('a manager changes consent but cannot export or anonymise', async () => {
    const el = await render(['VIEW_CUSTOMERS', 'MANAGE_CUSTOMERS', 'MANAGE_MARKETING_CONSENT']);

    expect(el.textContent).toContain('Aceita receber');
    expect(el.textContent).not.toContain('Exportar dados');
  });
});
