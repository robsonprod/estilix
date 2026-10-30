import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { Permission, ServiceItem } from '../../core/models';
import { SessionService } from '../../core/session.service';
import { ServicesPage } from './services.page';
import { signal } from '@angular/core';

/** Task 7.3: services by category, inactive apart, edit only with MANAGE_SERVICES. */
describe('ServicesPage', () => {
  const item = (name: string, category: string, extra: Partial<ServiceItem> = {}): ServiceItem => ({
    id: name, name, category, durationMinutes: 30, price: '45.00', priceType: 'FIXED', priceLabel: 'R$ 45,00', active: true, position: 0, ...extra,
  });

  async function render(permissions: Permission[]) {
    const api = {
      services: vi.fn().mockResolvedValue([
        item('Corte feminino', 'Cabelo'),
        item('Coloração', 'Cabelo', { priceType: 'FROM', priceLabel: 'a partir de R$ 150,00' }),
        item('Manicure', 'Unhas'),
        item('Pezinho', 'Cabelo', { active: false }),
      ]),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: api },
        { provide: SessionService, useValue: { currentTenantId: signal('t1'), permissions: signal(permissions) } },
      ],
    });
    const fixture = TestBed.createComponent(ServicesPage);
    fixture.detectChanges();
    for (let i = 0; i < 3; i++) {
      await new Promise((r) => setTimeout(r, 0));
      fixture.detectChanges();
    }
    return fixture.nativeElement as HTMLElement;
  }

  it('groups active services by category, shows "a partir de" and keeps inactive ones apart', async () => {
    const el = await render(['VIEW_SERVICES', 'MANAGE_SERVICES']);
    const titles = Array.from(el.querySelectorAll('h2')).map((h) => h.textContent?.trim());

    expect(titles).toEqual(['Cabelo', 'Unhas', 'Inativos']);
    expect(el.textContent).toContain('a partir de R$ 150,00');
    expect(el.textContent).toContain('Novo serviço');
  });

  it('a professional only reads', async () => {
    const el = await render(['VIEW_SERVICES']);

    expect(el.textContent).not.toContain('Novo serviço');
    expect(el.textContent).not.toContain('Editar');
  });
});
