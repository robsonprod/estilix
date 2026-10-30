import { ChangeDetectionStrategy, Component, inject, input, OnInit, signal } from '@angular/core';
import { BOOKING_STATUS_LABEL, GroupView, Problem, PublicBookingItem } from '../../core/models';
import { longDate, SchedulingApi } from '../../core/scheduling-api.service';
import { addMonths, Calendar } from '../../shared/calendar';
import { TimeGrid } from '../../shared/time-grid';
import { monthBounds, noticeLabel } from './booking-flow';

/** `/agendamento/:token`: the customer's bookings, cancel and reschedule within the salon's notice. */
@Component({
  selector: 'app-manage-booking',
  imports: [Calendar, TimeGrid],
  template: `
    <div class="booking">
      @if (error(); as e) {
        <div class="alert" [class.warn]="e.code === 'too-late' || e.code === 'slot-taken'" [class.error]="e.code !== 'too-late' && e.code !== 'slot-taken'" role="alert">
          {{ e.detail }}
          @if (e.code === 'too-late' && view()?.salonPhone) { <div><strong>Telefone do salão: {{ view()!.salonPhone }}</strong></div> }
        </div>
      }
      @if (view(); as v) {
        <header>
          <h1>{{ v.salonName }}</h1>
          <p class="muted small">Você pode remarcar ou cancelar até {{ noticeLabel(v.cancelNoticeMinutes) }} antes do horário.</p>
        </header>
        <ul class="list card">
          @for (b of v.bookings; track b.id) {
            <li class="stack">
              <div class="row">
                <div class="grow">
                  <strong>{{ b.serviceName }}</strong> com {{ b.professionalName }}
                  <div class="muted small">{{ longDate(b.date) }} · {{ b.start }}–{{ b.end }}</div>
                </div>
                <span class="badge" [class.ok]="b.status === 'CONFIRMED' || b.status === 'DONE'" [class.neutral]="b.status === 'CANCELLED' || b.status === 'NO_SHOW'">
                  {{ statusLabel[b.status] }}
                </span>
              </div>
              @if (b.status === 'CONFIRMED') {
                @if (b.canChange) {
                  <div class="row">
                    <button type="button" class="btn secondary" (click)="startReschedule(b)">Remarcar</button>
                    <button type="button" class="btn danger" (click)="cancel(b)" [disabled]="busy()">Cancelar</button>
                  </div>
                } @else {
                  <p class="muted small">Prazo para mudanças encerrado. Fale com o salão{{ v.salonPhone ? ': ' + v.salonPhone : '' }}.</p>
                }
              }
              @if (moving()?.id === b.id) {
                <div class="stack">
                  <app-calendar [month]="month()" [enabled]="days()" [selected]="date()" [minMonth]="bounds.first" [maxMonth]="bounds.last"
                    (pick)="chooseDay($event)" (monthChange)="moveMonth($event)" />
                  @if (date()) {
                    <app-time-grid [free]="free()" [unavailable]="unavailable()" [selected]="time()" (pick)="time.set($event)" />
                  }
                  <div class="row">
                    <button type="button" class="btn ghost" (click)="moving.set(null)">Desistir</button>
                    <span class="spacer"></span>
                    <button type="button" class="btn" (click)="confirmReschedule()" [disabled]="!time() || busy()">Confirmar novo horário</button>
                  </div>
                </div>
              }
            </li>
          }
        </ul>
        <a class="btn ghost" [href]="'/agendar/' + v.salonSlug">Fazer outro agendamento</a>
      } @else if (!error()) {
        <p class="muted">Carregando…</p>
      }
    </div>
  `,
  styles: `.booking { max-width: 560px; margin: 0 auto; padding: 16px; display: grid; gap: 16px; } .grow { flex: 1; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ManageBookingPage implements OnInit {
  private readonly api = inject(SchedulingApi);
  readonly token = input.required<string>();

  protected readonly longDate = longDate;
  protected readonly statusLabel = BOOKING_STATUS_LABEL;
  protected readonly noticeLabel = noticeLabel;
  protected readonly view = signal<GroupView | null>(null);
  protected readonly error = signal<Problem | null>(null);
  protected readonly busy = signal(false);
  protected readonly moving = signal<PublicBookingItem | null>(null);
  protected readonly month = signal('');
  protected readonly days = signal<string[]>([]);
  protected readonly date = signal<string | null>(null);
  protected readonly free = signal<string[]>([]);
  protected readonly unavailable = signal<string[]>([]);
  protected readonly time = signal<string | null>(null);
  protected bounds = monthBounds(new Date(), 30);

  async ngOnInit(): Promise<void> {
    await this.run(async () => this.view.set(await this.api.group(this.token())));
  }

  protected cancel(b: PublicBookingItem): Promise<void> {
    if (!confirm(`Cancelar ${b.serviceName} em ${longDate(b.date)} às ${b.start}?`)) return Promise.resolve();
    return this.run(async () => this.view.set(await this.api.cancel(this.token(), b.id)));
  }

  protected async startReschedule(b: PublicBookingItem): Promise<void> {
    this.moving.set(b);
    this.date.set(null);
    this.time.set(null);
    this.month.set(this.bounds.first);
    await this.loadDays();
  }

  protected async moveMonth(delta: number): Promise<void> {
    this.month.set(addMonths(this.month(), delta));
    await this.loadDays();
  }

  protected async chooseDay(date: string): Promise<void> {
    const b = this.moving()!;
    this.date.set(date);
    this.time.set(null);
    await this.run(async () => {
      const t = await this.api.times(this.view()!.salonSlug, b.serviceId, b.professionalId, date);
      this.free.set(t.free);
      this.unavailable.set(t.unavailable);
    });
  }

  protected confirmReschedule(): Promise<void> {
    const b = this.moving()!;
    return this.run(async () => {
      this.view.set(await this.api.reschedule(this.token(), b.id, this.date()!, this.time()!));
      this.moving.set(null);
    });
  }

  private loadDays(): Promise<void> {
    const b = this.moving()!;
    return this.run(async () => this.days.set((await this.api.days(this.view()!.salonSlug, b.serviceId, b.professionalId, this.month())).days));
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(e as Problem);
    } finally {
      this.busy.set(false);
    }
  }
}
