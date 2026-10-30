import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
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
});
