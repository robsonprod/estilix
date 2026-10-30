import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GroupView, PublicSalon } from '../../core/models';
import { SchedulingApi } from '../../core/scheduling-api.service';
import { phoneLooksValid } from './booking-flow';
import { PublicBookingPage } from './public-booking.page';

/** Tasks 7.1 and 7.2: the public booking flow. */
describe('PublicBookingPage', () => {
  const salon: PublicSalon = {
    name: 'Salão Bela Vista', slug: 'salao-bela-vista', phone: '+55 85 3333-0001', city: 'Fortaleza', daysAhead: 30, cancelNoticeMinutes: 120,
    services: [{ id: 's1', name: 'Corte', category: 'Cabelo', durationMinutes: 30, priceLabel: 'R$ 45,00' }],
    professionals: [
      { id: 'p1', displayName: 'Paula', serviceIds: ['s1'] },
      { id: 'p2', displayName: 'Rosa', serviceIds: ['s1'] },
    ],
  };
  const confirmed: GroupView = {
    salonName: 'Salão Bela Vista', salonSlug: 'salao-bela-vista', salonPhone: null, cancelNoticeMinutes: 120, code: '482913', token: 'tok',
    bookings: [{ id: 'b1', serviceId: 's1', professionalId: 'p1', serviceName: 'Corte', professionalName: 'Paula', date: '2026-11-03', start: '15:00', end: '15:30', status: 'CONFIRMED', canChange: true }],
  };
  let api: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(() => {
    api = {
      salon: vi.fn().mockResolvedValue(salon),
      days: vi.fn().mockResolvedValue({ month: '2026-11', days: ['2026-11-03', '2026-11-04'] }),
      times: vi.fn().mockResolvedValue({ date: '2026-11-03', free: ['15:00', '15:30'], unavailable: ['14:30'] }),
      book: vi.fn().mockResolvedValue(confirmed),
      next: vi.fn(),
      lookup: vi.fn(),
    };
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: SchedulingApi, useValue: api }] });
  });

  async function render() {
    const fixture = TestBed.createComponent(PublicBookingPage);
    fixture.componentRef.setInput('slug', 'salao-bela-vista');
    const settle = async () => {
      for (let i = 0; i < 5; i++) {
        await new Promise((r) => setTimeout(r, 0));
        fixture.detectChanges();
      }
    };
    fixture.detectChanges();
    await settle();
    const page = fixture.componentInstance as unknown as Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any
    return { fixture, settle, page, el: fixture.nativeElement as HTMLElement };
  }

  it('after choosing service and "qualquer profissional", the calendar opens on the first bookable day with times', async () => {
    const { page, settle, el } = await render();
    page['chooseService']('s1');
    await settle();
    page['chooseProfessional'](null);
    await settle();

    expect(api['days']).toHaveBeenCalledWith('salao-bela-vista', 's1', null, expect.stringMatching(/^\d{4}-\d{2}$/));
    expect(page['date']()).toBe('2026-11-03');
    const times = Array.from(el.querySelectorAll('app-time-grid button')) as HTMLButtonElement[];
    expect(times.map((b) => [b.textContent?.trim(), b.disabled])).toEqual([['14:30', true], ['15:00', false], ['15:30', false]]);
  });

  it('a time taken meanwhile shows the message and reloads the day', async () => {
    const { page, settle, el } = await render();
    page['chooseService']('s1');
    await settle();
    page['chooseProfessional']('p1');
    await settle();
    page['time'].set('15:00');
    page['name'] = 'Ana Souza';
    page['phone'] = '(85) 99876-5432';
    api['book'].mockRejectedValueOnce({ code: 'slot-taken', title: 'Horário indisponível', detail: 'Esse horário acabou de ser preenchido. Escolha outro horário.' });
    api['times'].mockResolvedValueOnce({ date: '2026-11-03', free: ['15:30'], unavailable: ['14:30', '15:00'] });

    await page['confirm']();
    await settle();

    expect(el.textContent).toContain('Esse horário acabou de ser preenchido');
    expect(page['time']()).toBeNull();
    expect(page['unavailable']()).toContain('15:00');
  });

  it('confirmation shows the code, the manage link and "Adicionar mais um serviço"', async () => {
    const { page, settle, el } = await render();
    page['chooseService']('s1');
    page['chooseProfessional']('p1');
    await settle();
    page['time'].set('15:00');
    page['name'] = 'Ana Souza';
    page['phone'] = '85998765432';

    await page['confirm']();
    await settle();

    expect(el.textContent).toContain('482 913');
    expect(el.textContent).toContain('/agendamento/tok');
    expect(el.textContent).toContain('Adicionar mais um serviço');
  });

  it('an invalid phone never calls the API', async () => {
    const { page } = await render();
    page['phone'] = '9988';
    await page['confirm']();

    expect(api['book']).not.toHaveBeenCalled();
    expect(phoneLooksValid('(85) 99876-5432')).toBe(true);
    expect(phoneLooksValid('+55 85 3333-4444')).toBe(true);
  });
});
