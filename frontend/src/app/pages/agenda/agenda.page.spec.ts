import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { CustomersApi } from '../../core/customers-api.service';
import { AgendaColumn, AgendaDay } from '../../core/models';
import { SchedulingApi } from '../../core/scheduling-api.service';
import { SessionService } from '../../core/session.service';
import { dayRange, isFree, timeAt } from './agenda-model';
import { AgendaPage } from './agenda.page';

/** Task 8.1: staff agenda. */
describe('agenda model', () => {
  const column: AgendaColumn = {
    professionalId: 'p1', displayName: 'Paula', color: '#8b2f5c',
    hours: [{ weekday: 2, start: '09:00', end: '12:00' }, { weekday: 2, start: '14:00', end: '18:00' }],
    items: [{ id: 'b1', kind: 'BOOKING', start: '10:00', end: '10:30', status: 'CONFIRMED', serviceName: 'Corte', customerName: 'Ana', customerPhone: '+5585', origin: 'PUBLIC', blockReason: null, cancelledBy: null }],
  };

  it('a tap maps to a 15-minute time from the top of the day', () => {
    expect(timeAt(0, 9 * 60)).toBe('09:00');
    expect(timeAt(1.2 * 50, 9 * 60)).toBe('09:45');
  });

  it('free means inside the hours and not covered by a confirmed booking', () => {
    expect(isFree(column, '09:00')).toBe(true);
    expect(isFree(column, '10:15')).toBe(false);
    expect(isFree(column, '12:30')).toBe(false); // lunch break
  });

  it('the visible range covers every professional, whole hours', () => {
    expect(dayRange([column])).toEqual({ from: 9 * 60, to: 18 * 60 });
    expect(dayRange([])).toEqual({ from: 8 * 60, to: 20 * 60 });
  });
});

describe('AgendaPage', () => {
  async function render(day: AgendaDay, permissions: string[]) {
    TestBed.configureTestingModule({
      providers: [
        { provide: SchedulingApi, useValue: { agenda: vi.fn().mockResolvedValue(day) } },
        { provide: CustomersApi, useValue: {} },
        { provide: ApiService, useValue: { services: vi.fn().mockResolvedValue([]), professionals: vi.fn().mockResolvedValue([]) } },
        { provide: SessionService, useValue: { currentTenantId: signal('t1'), permissions: signal(permissions) } },
      ],
    });
    const fixture = TestBed.createComponent(AgendaPage);
    fixture.detectChanges();
    for (let i = 0; i < 4; i++) {
      await new Promise((r) => setTimeout(r, 0));
      fixture.detectChanges();
    }
    return fixture.nativeElement as HTMLElement;
  }

  const paula: AgendaColumn = {
    professionalId: 'p1', displayName: 'Paula', color: '#8b2f5c', hours: [{ weekday: 2, start: '09:00', end: '18:00' }],
    items: [{ id: 'b1', kind: 'BOOKING', start: '10:00', end: '10:30', status: 'CONFIRMED', serviceName: 'Corte', customerName: 'Ana Souza', customerPhone: '+5585998765432', origin: 'PUBLIC', blockReason: null, cancelledBy: null }],
  };

  it('shows the bookings of each professional', async () => {
    const el = await render({ date: '2026-11-03', timeZone: 'America/Fortaleza', professionals: [paula], linkedProfessionalMissing: false }, ['VIEW_AGENDA', 'MANAGE_AGENDA']);

    expect(el.textContent).toContain('Paula');
    expect(el.textContent).toContain('Ana Souza · Corte');
    expect(el.textContent).toContain('+ Agendar');
  });

  it('explains when a professional is not linked yet', async () => {
    const el = await render({ date: '2026-11-03', timeZone: 'America/Fortaleza', professionals: [], linkedProfessionalMissing: true }, ['VIEW_AGENDA', 'MANAGE_AGENDA']);

    expect(el.textContent).toContain('ainda não está ligada a um profissional');
  });

  it('read-only roles see no booking actions', async () => {
    const el = await render({ date: '2026-11-03', timeZone: 'America/Fortaleza', professionals: [paula], linkedProfessionalMissing: false }, ['VIEW_AGENDA']);

    expect(el.textContent).not.toContain('+ Agendar');
  });

  // Attendance (change customers-and-service-records, task 5.2) ------------------------------------

  function dayWith(status: 'CONFIRMED' | 'IN_SERVICE' | 'DONE' | 'NO_SHOW', start = '00:00'): AgendaDay {
    return {
      date: '2026-11-03', timeZone: 'America/Sao_Paulo', linkedProfessionalMissing: false,
      professionals: [{ ...paula, hours: [{ weekday: 2, start: '00:00', end: '24:00' }],
        items: [{ ...paula.items[0], start, end: start === '00:00' ? '00:30' : '23:59', status, customerId: 'c1' }] }],
    };
  }

  /** Renders, opens the booking's detail and returns the element after the panel shows. */
  async function openFirstItem(day: AgendaDay, permissions: string[]) {
    TestBed.configureTestingModule({
      providers: [
        { provide: SchedulingApi, useValue: { agenda: vi.fn().mockResolvedValue(day) } },
        { provide: CustomersApi, useValue: {} },
        { provide: ApiService, useValue: { services: vi.fn().mockResolvedValue([]), professionals: vi.fn().mockResolvedValue([]) } },
        { provide: SessionService, useValue: { currentTenantId: signal('t1'), permissions: signal(permissions) } },
      ],
    });
    const fixture = TestBed.createComponent(AgendaPage);
    const el = fixture.nativeElement as HTMLElement;
    const step = async () => {
      for (let i = 0; i < 4; i++) {
        await new Promise((r) => setTimeout(r, 0));
        fixture.detectChanges();
      }
    };
    fixture.detectChanges();
    await step();
    (el.querySelector('.item') as HTMLButtonElement).click();
    await step();
    return el;
  }

  it('a confirmed booking of today offers "Iniciar atendimento" and, once started, "Não veio"', async () => {
    const el = await openFirstItem(dayWith('CONFIRMED'), ['VIEW_AGENDA', 'MANAGE_AGENDA', 'VIEW_SERVICE_RECORDS', 'MANAGE_SERVICE_RECORDS']);
    const texts = () => [...el.querySelectorAll('button')].map((b) => b.textContent?.trim());

    expect(el.textContent).toContain('Novo atendimento');
    expect(texts()).toContain('Iniciar atendimento');
    expect(texts()).toContain('Não veio');
  });

  it('a booking still ahead today has no "Não veio" yet', async () => {
    const el = await openFirstItem(dayWith('CONFIRMED', '23:59'), ['VIEW_AGENDA', 'MANAGE_AGENDA', 'MANAGE_SERVICE_RECORDS']);

    expect(el.textContent).toContain('Iniciar atendimento');
    expect(el.textContent).not.toContain('Não veio');
  });

  it('a booking in service opens its comanda instead', async () => {
    const el = await openFirstItem(dayWith('IN_SERVICE'), ['VIEW_AGENDA', 'MANAGE_AGENDA', 'VIEW_SERVICE_RECORDS', 'MANAGE_SERVICE_RECORDS']);

    expect(el.textContent).toContain('Em atendimento');
    expect(el.textContent).toContain('Abrir comanda');
    expect(el.textContent).not.toContain('Iniciar atendimento');
  });

  it('without record permissions there is no attendance action', async () => {
    const el = await openFirstItem(dayWith('CONFIRMED'), ['VIEW_AGENDA']);

    expect(el.textContent).not.toContain('Iniciar atendimento');
    expect(el.textContent).not.toContain('Novo atendimento');
  });

  it('a walk-in over a booking shows the slot-taken message', async () => {
    const walkIn = vi.fn().mockRejectedValue({ code: 'slot-taken', title: 'Horário indisponível', detail: 'Este profissional tem um agendamento às 14:30.' });
    TestBed.configureTestingModule({
      providers: [
        { provide: SchedulingApi, useValue: { agenda: vi.fn().mockResolvedValue(dayWith('CONFIRMED')) } },
        { provide: CustomersApi, useValue: { walkIn, lookup: vi.fn().mockRejectedValue({ code: 'not-found' }) } },
        {
          provide: ApiService,
          useValue: {
            services: vi.fn().mockResolvedValue([{ id: 's1', name: 'Corte', category: 'Cabelo', durationMinutes: 30, price: '50.00', priceType: 'FIXED', priceLabel: 'R$ 50,00', active: true, position: 0 }]),
            professionals: vi.fn().mockResolvedValue([{ id: 'p1', displayName: 'Paula', userId: null, color: '#8b2f5c', active: true, ownHours: false, serviceIds: ['s1'] }]),
          },
        },
        { provide: SessionService, useValue: { currentTenantId: signal('t1'), permissions: signal(['VIEW_AGENDA', 'MANAGE_AGENDA', 'MANAGE_SERVICE_RECORDS']) } },
      ],
    });
    const fixture = TestBed.createComponent(AgendaPage);
    const el = fixture.nativeElement as HTMLElement;
    const step = async () => {
      for (let i = 0; i < 4; i++) {
        await new Promise((r) => setTimeout(r, 0));
        fixture.detectChanges();
      }
    };
    fixture.detectChanges();
    await step();

    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Novo atendimento')!.click();
    await step();
    const inputs = el.querySelectorAll('.panel input');
    (inputs[0] as HTMLInputElement).value = '(11) 97777-1111';
    inputs[0].dispatchEvent(new Event('input'));
    (inputs[1] as HTMLInputElement).value = 'Bia Lima';
    inputs[1].dispatchEvent(new Event('input'));
    await step();
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Iniciar')!.click();
    await step();

    expect(walkIn).toHaveBeenCalledWith('t1', { phone: '(11) 97777-1111', name: 'Bia Lima', serviceId: 's1', professionalId: 'p1' });
    expect(el.textContent).toContain('agendamento às 14:30');
  });
});
