import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { DecimalPipe, KeyValuePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { CustomersApi } from '../../core/customers-api.service';
import { AgendaColumn, AgendaDay, AgendaItem, BOOKING_STATUS_LABEL, Problem, Professional, ServiceItem, ServiceRecord } from '../../core/models';
import { isoDate, longDate, SchedulingApi } from '../../core/scheduling-api.service';
import { SessionService } from '../../core/session.service';
import { RecordSheet } from '../records/record-sheet';
import { dayRange, hasStarted, isFree, itemLabel, PX_PER_MINUTE, shiftDate, timeAt, toMinutes } from './agenda-model';

type Panel =
  | { kind: 'book'; professionalId: string; date: string; time: string | null; serviceId: string | null; name: string; phone: string; times: string[] }
  | { kind: 'item'; column: AgendaColumn; item: AgendaItem; moveDate: string; moveTime: string | null; moveProfessionalId: string; times: string[] }
  | { kind: 'block'; professionalId: string; date: string; start: string; end: string; reason: string }
  | { kind: 'walkin'; phone: string; name: string; knownName: string | null; serviceId: string | null; professionalId: string }
  | { kind: 'record'; record: ServiceRecord };

/** "Agenda" (spec scheduling/staff-agenda): the day per professional, booking, moving, cancelling and blocking. */
@Component({
  selector: 'app-agenda',
  imports: [FormsModule, DecimalPipe, KeyValuePipe, RecordSheet],
  templateUrl: './agenda.page.html',
  styleUrl: './agenda.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgendaPage implements OnInit {
  private readonly scheduling = inject(SchedulingApi);
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  private readonly records = inject(CustomersApi);

  protected readonly longDate = longDate;
  protected readonly itemLabel = itemLabel;
  protected readonly px = PX_PER_MINUTE;
  protected readonly toMinutes = toMinutes;
  protected readonly date = signal(isoDate(new Date()));
  protected readonly day = signal<AgendaDay | null>(null);
  protected readonly services = signal<ServiceItem[]>([]);
  protected readonly pros = signal<Professional[]>([]);
  protected readonly focused = signal<string | null>(null); // phone: one professional at a time
  protected readonly panel = signal<Panel | null>(null);
  protected readonly error = signal<Problem | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly statusLabel = BOOKING_STATUS_LABEL;
  protected readonly canManage = computed(() => this.session.permissions().includes('MANAGE_AGENDA'));
  protected readonly canRecord = computed(() => this.session.permissions().includes('MANAGE_SERVICE_RECORDS'));
  protected readonly canViewRecords = computed(() => this.session.permissions().includes('VIEW_SERVICE_RECORDS'));
  protected readonly isToday = computed(() => this.date() === isoDate(new Date()));
  /** A PROFESSIONAL only sees their own column: that is the professional they act as. */
  protected readonly ownProfessionalId = computed(() =>
    this.session.currentTenant?.()?.role === 'PROFESSIONAL' ? (this.columns()[0]?.professionalId ?? null) : null,
  );
  protected readonly columns = computed(() => this.day()?.professionals ?? []);
  protected readonly range = computed(() => dayRange(this.columns()));
  protected readonly hours = computed(() => {
    const r = this.range();
    return Array.from({ length: (r.to - r.from) / 60 }, (_, i) => r.from + i * 60);
  });
  protected readonly focusedId = computed(() => this.focused() ?? this.columns()[0]?.professionalId ?? null);

  private get tenantId(): string {
    return this.session.currentTenantId()!;
  }

  async ngOnInit(): Promise<void> {
    await this.run(async () => {
      const [services, pros] = await Promise.all([this.api.services(this.tenantId), this.api.professionals(this.tenantId)]);
      this.services.set(services.filter((s) => s.active));
      this.pros.set(pros);
      await this.load();
    });
  }

  protected async go(days: number | 'today'): Promise<void> {
    this.date.set(days === 'today' ? isoDate(new Date()) : shiftDate(this.date(), days));
    this.panel.set(null);
    await this.run(() => this.load());
  }

  protected servicesOf(professionalId: string): ServiceItem[] {
    const ids = this.pros().find((p) => p.id === professionalId)?.serviceIds ?? [];
    return this.services().filter((s) => ids.includes(s.id));
  }

  protected top(time: string): number {
    return (toMinutes(time) - this.range().from) * this.px;
  }

  protected height(item: AgendaItem): number {
    return Math.max(22, (toMinutes(item.end) - toMinutes(item.start)) * this.px - 2);
  }

  protected inHours(column: AgendaColumn): { top: number; height: number }[] {
    return column.hours.map((h) => ({ top: this.top(h.start), height: (toMinutes(h.end) - toMinutes(h.start)) * this.px }));
  }

  /** Tapping an empty spot of a column opens the booking form with that time (spec "Staff-created bookings"). */
  protected tapColumn(column: AgendaColumn, event: MouseEvent): void {
    if (!this.canManage() || (event.target as HTMLElement).closest('.item')) return;
    const time = timeAt((event as MouseEvent & { offsetY: number }).offsetY, this.range().from);
    if (!isFree(column, time)) return;
    void this.openBook(column.professionalId, time);
  }

  protected async openBook(professionalId: string, time: string | null = null): Promise<void> {
    const serviceId = this.servicesOf(professionalId)[0]?.id ?? null;
    this.panel.set({ kind: 'book', professionalId, date: this.date(), time, serviceId, name: '', phone: '', times: [] });
    await this.refreshBookTimes();
  }

  protected async refreshBookTimes(): Promise<void> {
    const p = this.panel();
    if (p?.kind !== 'book' || !p.serviceId) return;
    try {
      const t = await this.scheduling.staffTimes(this.tenantId, p.serviceId, p.professionalId, p.date);
      const keep = p.time && t.free.includes(p.time) ? p.time : null;
      this.panel.set({ ...p, times: t.free, time: keep ?? t.free[0] ?? null });
    } catch (e) {
      this.error.set(e as Problem);
    }
  }

  protected book(): Promise<void> {
    const p = this.panel();
    if (p?.kind !== 'book' || !p.serviceId || !p.time) return Promise.resolve();
    return this.run(async () => {
      const r = await this.scheduling.staffBook(this.tenantId, {
        serviceId: p.serviceId!, professionalId: p.professionalId, date: p.date, time: p.time!, name: p.name.trim(), phone: p.phone.trim(),
      });
      this.panel.set(null);
      this.notice.set(r.code ? `Agendado. Código da cliente: ${r.code.slice(0, 3)} ${r.code.slice(3)}` : 'Agendado.');
      await this.load();
    });
  }

  protected async openItem(column: AgendaColumn, item: AgendaItem): Promise<void> {
    this.panel.set({ kind: 'item', column, item, moveDate: this.date(), moveTime: null, moveProfessionalId: column.professionalId, times: [] });
  }

  protected async refreshMoveTimes(): Promise<void> {
    const p = this.panel();
    if (p?.kind !== 'item') return;
    const serviceId = this.services().find((s) => s.name === p.item.serviceName)?.id;
    if (!serviceId) return;
    try {
      const t = await this.scheduling.staffTimes(this.tenantId, serviceId, p.moveProfessionalId, p.moveDate);
      this.panel.set({ ...p, times: t.free, moveTime: null });
    } catch (e) {
      this.error.set(e as Problem);
    }
  }

  protected cancelItem(): Promise<void> {
    const p = this.panel();
    if (p?.kind !== 'item' || !confirm('Cancelar este agendamento?')) return Promise.resolve();
    return this.run(async () => {
      await this.scheduling.staffCancel(this.tenantId, p.item.id);
      this.panel.set(null);
      this.notice.set('Agendamento cancelado.');
      await this.load();
    });
  }

  protected moveItem(): Promise<void> {
    const p = this.panel();
    if (p?.kind !== 'item' || !p.moveTime) return Promise.resolve();
    return this.run(async () => {
      await this.scheduling.staffReschedule(this.tenantId, p.item.id, p.moveDate, p.moveTime!, p.moveProfessionalId);
      this.panel.set(null);
      this.notice.set('Agendamento remarcado.');
      await this.load();
    });
  }

  // Attendance (change customers-and-service-records) ---------------------------------------------

  protected started(item: AgendaItem): boolean {
    return hasStarted(this.date(), item.start, new Date());
  }

  /** "Iniciar atendimento": the booking becomes "Em atendimento" and the comanda opens, prices filled in. */
  protected startService(): Promise<void> {
    const p = this.panel();
    if (p?.kind !== 'item') return Promise.resolve();
    return this.run(async () => {
      const record = await this.records.startFromBooking(this.tenantId, p.item.id);
      this.panel.set({ kind: 'record', record });
      await this.load();
    });
  }

  protected openRecord(): Promise<void> {
    const p = this.panel();
    if (p?.kind !== 'item') return Promise.resolve();
    return this.run(async () => this.panel.set({ kind: 'record', record: await this.records.recordOfEntry(this.tenantId, p.item.id) }));
  }

  protected noShow(): Promise<void> {
    const p = this.panel();
    if (p?.kind !== 'item' || !confirm('Marcar que a cliente não veio? O horário fica livre de novo.')) return Promise.resolve();
    return this.run(async () => {
      await this.scheduling.noShow(this.tenantId, p.item.id);
      this.panel.set(null);
      this.notice.set('Marcado como "não veio". O horário está livre.');
      await this.load();
    });
  }

  protected openWalkIn(): void {
    const professionalId = this.ownProfessionalId() ?? this.focusedId() ?? this.pros().find((p) => p.active)?.id;
    if (!professionalId) return;
    this.panel.set({ kind: 'walkin', phone: '', name: '', knownName: null, serviceId: this.servicesOf(professionalId)[0]?.id ?? null, professionalId });
  }

  /** Known phone: the name appears and is not asked again (spec "Known phone fills the name"). */
  protected async lookupPhone(): Promise<void> {
    const p = this.panel();
    if (p?.kind !== 'walkin') return;
    if (p.phone.replace(/\D/g, '').length < 10) {
      this.patch({ knownName: null });
      return;
    }
    try {
      const known = await this.records.lookup(this.tenantId, p.phone);
      this.patch({ knownName: known.name });
    } catch {
      this.patch({ knownName: null });
    }
  }

  protected startWalkIn(): Promise<void> {
    const p = this.panel();
    if (p?.kind !== 'walkin' || !p.serviceId) return Promise.resolve();
    return this.run(async () => {
      const record = await this.records.walkIn(this.tenantId, {
        phone: p.phone.trim(), name: p.knownName ? null : p.name.trim(), serviceId: p.serviceId!, professionalId: p.professionalId,
      });
      this.panel.set({ kind: 'record', record });
      await this.load();
    });
  }

  protected async recordChanged(record: ServiceRecord): Promise<void> {
    this.panel.set({ kind: 'record', record });
    if (record.status === 'CLOSED') this.notice.set('Atendimento concluído.');
    await this.run(() => this.load());
  }

  protected removeBlock(): Promise<void> {
    const p = this.panel();
    if (p?.kind !== 'item') return Promise.resolve();
    return this.run(async () => {
      await this.scheduling.removeBlock(this.tenantId, p.item.id);
      this.panel.set(null);
      await this.load();
    });
  }

  protected openBlock(professionalId: string): void {
    this.panel.set({ kind: 'block', professionalId, date: this.date(), start: '12:00', end: '13:00', reason: '' });
  }

  protected saveBlock(): Promise<void> {
    const p = this.panel();
    if (p?.kind !== 'block') return Promise.resolve();
    return this.run(async () => {
      await this.scheduling.block(this.tenantId, { professionalId: p.professionalId, date: p.date, start: p.start, end: p.end, reason: p.reason.trim() || null });
      this.panel.set(null);
      this.notice.set('Horário bloqueado.');
      await this.load();
    });
  }

  /** Signals hold immutable panels: copy with the changed field. */
  protected patch(changes: Record<string, unknown>): void {
    const p = this.panel();
    if (p) this.panel.set({ ...p, ...changes } as Panel);
  }

  private async load(): Promise<void> {
    this.day.set(await this.scheduling.agenda(this.tenantId, this.date()));
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(e as Problem);
    } finally {
      this.busy.set(false);
    }
  }
}
