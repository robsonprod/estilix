import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { GroupView } from '../../core/models';
import { SchedulingApi } from '../../core/scheduling-api.service';
import { ManageBookingPage } from './manage-booking.page';

/** Task 7.2: manage page — actions within the notice, salon phone when too late. */
describe('ManageBookingPage', () => {
  const view = (canChange: boolean): GroupView => ({
    salonName: 'Salão Bela Vista', salonSlug: 'salao-bela-vista', salonPhone: '+55 85 3333-0001', cancelNoticeMinutes: 120, code: null, token: null,
    bookings: [{ id: 'b1', serviceId: 's1', professionalId: 'p1', serviceName: 'Corte', professionalName: 'Paula', date: '2026-11-03', start: '15:00', end: '15:30', status: 'CONFIRMED', canChange }],
  });

  async function render(v: GroupView) {
    TestBed.configureTestingModule({ providers: [{ provide: SchedulingApi, useValue: { group: vi.fn().mockResolvedValue(v) } }] });
    const fixture = TestBed.createComponent(ManageBookingPage);
    fixture.componentRef.setInput('token', 'tok');
    fixture.detectChanges();
    for (let i = 0; i < 4; i++) {
      await new Promise((r) => setTimeout(r, 0));
      fixture.detectChanges();
    }
    return fixture.nativeElement as HTMLElement;
  }

  it('offers cancel and reschedule within the notice', async () => {
    const el = await render(view(true));

    expect(el.textContent).toContain('Remarcar');
    expect(el.textContent).toContain('Cancelar');
    expect(el.textContent).toContain('até 2 horas antes');
  });

  it('after the notice, points to the salon phone instead', async () => {
    const el = await render(view(false));

    expect(el.textContent).not.toContain('Remarcar');
    expect(el.textContent).toContain('+55 85 3333-0001');
  });
});
