import { AgendaColumn, AgendaItem, BookingStatus } from '../../core/models';

export const PX_PER_MINUTE = 1.2;

export function toMinutes(time: string): number {
  if (time === '24:00') return 1440;
  const [h, m] = time.split(':').map(Number);
  return h * 60 + m;
}

export function toTime(minutes: number): string {
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
}

/** Visible range of the day: from the earliest hour any visible professional works to the latest (default 08–20). */
export function dayRange(columns: AgendaColumn[]): { from: number; to: number } {
  const all = columns.flatMap((c) => [...c.hours.map((h) => [toMinutes(h.start), toMinutes(h.end)]), ...c.items.map((i) => [toMinutes(i.start), toMinutes(i.end)])]);
  if (!all.length) return { from: 8 * 60, to: 20 * 60 };
  const from = Math.floor(Math.min(...all.map((a) => a[0])) / 60) * 60;
  const to = Math.ceil(Math.max(...all.map((a) => a[1])) / 60) * 60;
  return { from, to: Math.max(to, from + 60) };
}

/** Grid time (15-minute steps) for a click at [offsetY] pixels inside a column that starts at [from]. */
export function timeAt(offsetY: number, from: number): string {
  const minute = from + Math.floor(offsetY / PX_PER_MINUTE / 15) * 15;
  return toTime(minute);
}

/** Statuses that hold time, as the database's exclusion constraint does (design D3). */
export const HELD: readonly BookingStatus[] = ['CONFIRMED', 'IN_SERVICE', 'DONE'];

/** Whether [time] is inside the professional's hours and not covered by an item that holds time. */
export function isFree(column: AgendaColumn, time: string): boolean {
  const m = toMinutes(time);
  const inHours = column.hours.some((h) => toMinutes(h.start) <= m && m < toMinutes(h.end));
  const taken = column.items.some((i) => HELD.includes(i.status) && toMinutes(i.start) <= m && m < toMinutes(i.end));
  return inHours && !taken;
}

export function itemLabel(item: AgendaItem): string {
  if (item.kind === 'BLOCK') return item.blockReason ? `Bloqueado · ${item.blockReason}` : 'Bloqueado';
  return `${item.customerName ?? ''} · ${item.serviceName ?? ''}`;
}

/** "2026-11-03" ± days. */
export function shiftDate(iso: string, days: number): string {
  const [y, m, d] = iso.split('-').map(Number);
  const date = new Date(y, m - 1, d + days);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

/** Whether a booking on [date] at [start] has already begun, by the device clock ("Não veio" needs it). */
export function hasStarted(date: string, start: string, now: Date): boolean {
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  if (date !== today) return date < today;
  return toMinutes(start) <= now.getHours() * 60 + now.getMinutes();
}
