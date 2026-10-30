import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { GroupView, Problem, PublicSalon } from '../../core/models';
import { longDate, SchedulingApi } from '../../core/scheduling-api.service';
import { addMonths, Calendar } from '../../shared/calendar';
import { TimeGrid } from '../../shared/time-grid';
import { monthBounds, noticeLabel, phoneLooksValid, professionalsFor } from './booking-flow';

/**
 * Public booking page `/agendar/:slug` (spec scheduling/public-booking): service → who → day → time
 * → name and phone → confirmed, with code, manage link and "Adicionar mais um serviço".
 */
@Component({
  selector: 'app-public-booking',
  imports: [FormsModule, Calendar, TimeGrid],
  templateUrl: './public-booking.page.html',
  styleUrl: './public-booking.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PublicBookingPage implements OnInit {
  private readonly api = inject(SchedulingApi);
  private readonly router = inject(Router);
  readonly slug = input.required<string>();

  protected readonly longDate = longDate;
  protected readonly noticeLabel = noticeLabel;
  protected readonly salon = signal<PublicSalon | null>(null);
  protected readonly notFound = signal(false);
  protected readonly error = signal<Problem | null>(null);
  protected readonly busy = signal(false);

  protected readonly serviceId = signal<string | null>(null);
  protected readonly professionalId = signal<string | null | undefined>(undefined); // undefined = not chosen yet, null = "qualquer"
  protected readonly month = signal('');
  protected readonly bounds = signal({ first: '', last: '' });
  protected readonly days = signal<string[]>([]);
  protected readonly date = signal<string | null>(null);
  protected readonly free = signal<string[]>([]);
  protected readonly unavailable = signal<string[]>([]);
  protected readonly time = signal<string | null>(null);
  protected name = '';
  protected phone = '';
  protected readonly phoneError = signal<string | null>(null);
  protected readonly done = signal<GroupView | null>(null);
  protected readonly token = signal<string | null>(null);
  protected readonly adding = signal(false);
  protected readonly lookupOpen = signal(false);
  protected lookupPhone = '';
  protected lookupCode = '';

  protected readonly service = computed(() => this.salon()?.services.find((s) => s.id === this.serviceId()) ?? null);
  protected readonly who = computed(() => {
    const s = this.salon();
    const id = this.serviceId();
    return s && id ? professionalsFor(s, id) : [];
  });
  protected readonly whoName = computed(() => {
    const id = this.professionalId();
    return id ? (this.who().find((p) => p.id === id)?.displayName ?? '') : 'Qualquer profissional';
  });
  protected readonly manageLink = computed(() => (this.token() ? `${location.origin}/agendamento/${this.token()}` : ''));

  async ngOnInit(): Promise<void> {
    try {
      const salon = await this.api.salon(this.slug());
      this.salon.set(salon);
      this.bounds.set(monthBounds(new Date(), salon.daysAhead));
      this.month.set(this.bounds().first);
    } catch (e) {
      if ((e as Problem).code === 'not-found') this.notFound.set(true);
      else this.error.set(e as Problem);
    }
  }

  protected chooseService(id: string): void {
    this.serviceId.set(id);
    this.professionalId.set(this.who().length === 1 ? this.who()[0].id : undefined);
    this.resetDay();
    if (this.professionalId() !== undefined) void this.loadDays();
  }

  protected chooseProfessional(id: string | null): void {
    this.professionalId.set(id);
    this.resetDay();
    void this.loadDays();
  }

  protected async moveMonth(delta: number): Promise<void> {
    this.month.set(addMonths(this.month(), delta));
    await this.loadDays();
  }

  protected async chooseDay(date: string): Promise<void> {
    this.date.set(date);
    this.time.set(null);
    await this.loadTimes();
  }

  protected async confirm(): Promise<void> {
    if (!phoneLooksValid(this.phone)) {
      this.phoneError.set('Informe o celular com DDD, por exemplo (85) 99876-5432.');
      return;
    }
    this.phoneError.set(null);
    await this.run(async () => {
      const view = await this.api.book(this.slug(), {
        serviceId: this.serviceId()!, professionalId: this.professionalId() ?? null, date: this.date()!, time: this.time()!,
        name: this.name.trim(), phone: this.phone.trim(),
      });
      this.token.set(view.token);
      this.done.set(view);
    });
  }

  /** "Adicionar mais um serviço": starts exactly when the previous one ends. */
  protected async addNext(serviceId: string): Promise<void> {
    await this.run(async () => {
      const view = await this.api.next(this.token()!, serviceId, null);
      this.done.set({ ...view, code: this.done()!.code, token: this.token() });
      this.adding.set(false);
    });
  }

  protected async lookup(): Promise<void> {
    await this.run(async () => {
      const view = await this.api.lookup(this.slug(), this.lookupPhone, this.lookupCode);
      await this.router.navigate(['/agendamento', view.token]);
    });
  }

  protected async copyLink(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.manageLink());
    } catch {
      /* the link is visible on screen anyway */
    }
  }

  private resetDay(): void {
    this.date.set(null);
    this.time.set(null);
    this.days.set([]);
  }

  private async loadDays(): Promise<void> {
    await this.run(async () => {
      const r = await this.api.days(this.slug(), this.serviceId()!, this.professionalId() ?? null, this.month());
      this.days.set(r.days);
      // Open on the first bookable day (spec "Booking flow in pt-BR").
      if (!this.date() && r.days.length) await this.chooseDay(r.days[0]);
    });
  }

  private async loadTimes(): Promise<void> {
    const t = await this.api.times(this.slug(), this.serviceId()!, this.professionalId() ?? null, this.date()!);
    this.free.set(t.free);
    this.unavailable.set(t.unavailable);
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      const problem = e as Problem;
      this.error.set(problem);
      // Someone else took the time: show the message and refresh the day (spec "Time taken meanwhile").
      if (problem.code === 'slot-taken' && this.date() && !this.done()) {
        this.time.set(null);
        await this.loadTimes().catch(() => undefined);
      }
    } finally {
      this.busy.set(false);
    }
  }
}
