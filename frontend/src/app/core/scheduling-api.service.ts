import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom, Observable } from 'rxjs';
import { toProblem } from './api.service';
import { AgendaDay, AgendaItem, BookingRules, DayTimes, GroupView, PublicSalon } from './models';

export interface BookingInput {
  serviceId: string;
  professionalId: string | null;
  date: string;
  time: string;
  name: string;
  phone: string;
}

/** Public booking (no login) and the staff agenda. */
@Injectable({ providedIn: 'root' })
export class SchedulingApi {
  private readonly http = inject(HttpClient);

  // Public ------------------------------------------------------------------------------------
  salon(slug: string): Promise<PublicSalon> {
    return this.call(this.http.get<PublicSalon>(`/api/v1/public/salons/${slug}`));
  }

  days(slug: string, serviceId: string, professionalId: string | null, month: string): Promise<{ month: string; days: string[] }> {
    return this.call(this.http.get<{ month: string; days: string[] }>(`/api/v1/public/salons/${slug}/days`, { params: this.params({ serviceId, professionalId, month }) }));
  }

  times(slug: string, serviceId: string, professionalId: string | null, date: string): Promise<DayTimes> {
    return this.call(this.http.get<DayTimes>(`/api/v1/public/salons/${slug}/times`, { params: this.params({ serviceId, professionalId, date }) }));
  }

  book(slug: string, input: BookingInput): Promise<GroupView> {
    return this.call(this.http.post<GroupView>(`/api/v1/public/salons/${slug}/bookings`, input));
  }

  lookup(slug: string, phone: string, code: string): Promise<GroupView> {
    return this.call(this.http.post<GroupView>(`/api/v1/public/salons/${slug}/lookup`, { phone, code }));
  }

  group(token: string): Promise<GroupView> {
    return this.call(this.http.get<GroupView>(`/api/v1/public/bookings/${token}`));
  }

  next(token: string, serviceId: string, professionalId: string | null): Promise<GroupView> {
    return this.call(this.http.post<GroupView>(`/api/v1/public/bookings/${token}/next`, { serviceId, professionalId }));
  }

  cancel(token: string, bookingId: string): Promise<GroupView> {
    return this.call(this.http.post<GroupView>(`/api/v1/public/bookings/${token}/${bookingId}/cancel`, {}));
  }

  reschedule(token: string, bookingId: string, date: string, time: string): Promise<GroupView> {
    return this.call(this.http.post<GroupView>(`/api/v1/public/bookings/${token}/${bookingId}/reschedule`, { date, time }));
  }

  // Staff -------------------------------------------------------------------------------------
  agenda(tenantId: string, date: string): Promise<AgendaDay> {
    return this.call(this.http.get<AgendaDay>(`/api/v1/tenants/${tenantId}/agenda`, { params: { date } }));
  }

  staffTimes(tenantId: string, serviceId: string, professionalId: string, date: string): Promise<DayTimes> {
    return this.call(this.http.get<DayTimes>(`/api/v1/tenants/${tenantId}/agenda/times`, { params: { serviceId, professionalId, date } }));
  }

  staffBook(tenantId: string, input: BookingInput): Promise<{ groupId: string; code: string | null; item: AgendaItem }> {
    return this.call(this.http.post<{ groupId: string; code: string | null; item: AgendaItem }>(`/api/v1/tenants/${tenantId}/agenda/bookings`, input));
  }

  staffCancel(tenantId: string, id: string): Promise<AgendaItem> {
    return this.call(this.http.post<AgendaItem>(`/api/v1/tenants/${tenantId}/agenda/bookings/${id}/cancel`, {}));
  }

  /** "Não veio": only after the booking's start (spec bookings "No-show frees the time"). */
  noShow(tenantId: string, id: string): Promise<AgendaItem> {
    return this.call(this.http.post<AgendaItem>(`/api/v1/tenants/${tenantId}/agenda/bookings/${id}/no-show`, {}));
  }

  staffReschedule(tenantId: string, id: string, date: string, time: string, professionalId: string | null): Promise<AgendaItem> {
    return this.call(this.http.post<AgendaItem>(`/api/v1/tenants/${tenantId}/agenda/bookings/${id}/reschedule`, { date, time, professionalId }));
  }

  block(tenantId: string, body: { professionalId: string; date: string; start: string; end: string; reason: string | null }): Promise<AgendaItem> {
    return this.call(this.http.post<AgendaItem>(`/api/v1/tenants/${tenantId}/agenda/blocks`, body));
  }

  removeBlock(tenantId: string, id: string): Promise<void> {
    return this.call(this.http.delete<void>(`/api/v1/tenants/${tenantId}/agenda/blocks/${id}`));
  }

  rules(tenantId: string): Promise<BookingRules> {
    return this.call(this.http.get<BookingRules>(`/api/v1/tenants/${tenantId}/booking-rules`));
  }

  updateRules(tenantId: string, rules: BookingRules): Promise<BookingRules> {
    return this.call(this.http.put<BookingRules>(`/api/v1/tenants/${tenantId}/booking-rules`, rules));
  }

  private params(values: Record<string, string | null>): Record<string, string> {
    return Object.fromEntries(Object.entries(values).filter(([, v]) => v !== null)) as Record<string, string>;
  }

  private async call<T>(request: Observable<T>): Promise<T> {
    try {
      return await firstValueFrom(request);
    } catch (error) {
      throw toProblem(error);
    }
  }
}

/** "2026-11-03" for a Date, in local time (the page shows the salon's own dates). */
export function isoDate(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** "Terça, 3 de novembro". */
export function longDate(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  const text = new Date(y, m - 1, d).toLocaleDateString('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' });
  return text.charAt(0).toUpperCase() + text.slice(1);
}
