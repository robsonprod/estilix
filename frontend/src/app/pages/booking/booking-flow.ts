import { PublicSalon } from '../../core/models';

/** First month a calendar shows ("2026-11") and the last month the window reaches. */
export function monthBounds(today: Date, daysAhead: number): { first: string; last: string } {
  const last = new Date(today.getFullYear(), today.getMonth(), today.getDate() + daysAhead);
  const fmt = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  return { first: fmt(today), last: fmt(last) };
}

/** Professionals who perform the service, for the "Com quem?" step. */
export function professionalsFor(salon: PublicSalon, serviceId: string) {
  return salon.professionals.filter((p) => p.serviceIds.includes(serviceId));
}

/** Front-end check mirroring the API: Brazilian phone with DDD (10 or 11 digits, optionally +55). */
export function phoneLooksValid(raw: string): boolean {
  const digits = raw.replace(/\D/g, '');
  const national = digits.length >= 12 && digits.startsWith('55') ? digits.slice(2) : digits;
  return (national.length === 10 || national.length === 11) && !national.startsWith('0');
}

/** "2 horas" / "30 minutos" for the cancel notice. */
export function noticeLabel(minutes: number): string {
  if (minutes % 60 === 0) {
    const h = minutes / 60;
    return `${h} ${h === 1 ? 'hora' : 'horas'}`;
  }
  return `${minutes} minutos`;
}
