import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { CustomersApi } from '../../core/customers-api.service';
import { ServiceRecord } from '../../core/models';
import { SessionService } from '../../core/session.service';
import { RecordSheet } from './record-sheet';

/** Task 5.3: the comanda closes in "Concluir" + payment method, prices already filled. */
describe('RecordSheet', () => {
  const open: ServiceRecord = {
    id: 'r1', customerId: 'c1', customerName: 'Ana Souza', customerPhone: '+5511988887777', status: 'OPEN', paymentMethod: null,
    total: '50.00', openedAt: '2026-11-03T18:00:00Z', closedAt: null, voidedAt: null,
    items: [{ id: 'i1', serviceId: 's1', serviceName: 'Corte', professionalId: 'p1', professionalName: 'Paula', price: '50.00', fromBooking: true, agendaEntryId: 'e1' }],
  };

  async function render(record: ServiceRecord, permissions: string[], api: Partial<CustomersApi> = {}) {
    TestBed.configureTestingModule({
      providers: [
        { provide: CustomersApi, useValue: api },
        { provide: SessionService, useValue: { currentTenantId: signal('t1'), permissions: signal(permissions) } },
      ],
    });
    const fixture = TestBed.createComponent(RecordSheet);
    fixture.componentRef.setInput('record', record);
    fixture.detectChanges();
    return fixture;
  }

  const click = (el: HTMLElement, text: string) =>
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)!.click();

  it('closes with Pix in two taps and reports the closed record', async () => {
    const closed = { ...open, status: 'CLOSED' as const, paymentMethod: 'PIX' as const };
    const close = vi.fn().mockResolvedValue(closed);
    const fixture = await render(open, ['MANAGE_SERVICE_RECORDS'], { close });
    const emitted: ServiceRecord[] = [];
    fixture.componentInstance.changed.subscribe((r) => emitted.push(r));
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('R$ 50,00');
    click(el, 'Concluir');
    fixture.detectChanges();
    click(el, 'Pix');
    await new Promise((r) => setTimeout(r, 0));

    expect(close).toHaveBeenCalledWith('t1', 'r1', 'PIX');
    expect(emitted[0].status).toBe('CLOSED');
  });

  it('read-only roles see the amounts but no actions', async () => {
    const fixture = await render(open, ['VIEW_SERVICE_RECORDS']);
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('R$ 50,00');
    expect(el.textContent).not.toContain('Concluir');
    expect(el.textContent).not.toContain('Anular comanda');
    expect(el.querySelector('input')).toBeNull();
  });

  it('only owners and managers can void', async () => {
    const manager = (await render(open, ['MANAGE_SERVICE_RECORDS', 'VOID_SERVICE_RECORDS'])).nativeElement as HTMLElement;
    expect(manager.textContent).toContain('Anular comanda');
    TestBed.resetTestingModule();
    const pro = (await render(open, ['MANAGE_SERVICE_RECORDS'])).nativeElement as HTMLElement;
    expect(pro.textContent).not.toContain('Anular comanda');
  });

  it('a wrong price is caught before calling the API', async () => {
    const updatePrice = vi.fn();
    const fixture = await render(open, ['MANAGE_SERVICE_RECORDS'], { updatePrice });
    const input = (fixture.nativeElement as HTMLElement).querySelector('input')!;

    input.value = 'abc';
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(updatePrice).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Valor inválido');
  });
});
