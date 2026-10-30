import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { EMPTY_ADDRESS, EstablishmentType, HoursInterval, OnboardingRequest, Problem, WizardDefaults } from '../../core/models';
import { subscribeIntent } from '../../core/navigation';
import { SessionService } from '../../core/session.service';
import { HoursEditor } from '../../shared/hours-editor';
import { choicesFrom, ServiceChoice, stepOf, toBrazilian, toDecimal, TYPE_OPTIONS } from './wizard-model';

/**
 * Sign-up wizard (spec tenant-onboarding): 3 screens, everything prefilled, one request at the end.
 * With `?corrigir=1` it edits a rejected salon (no services step) and resubmits it.
 */
@Component({
  selector: 'app-wizard',
  imports: [FormsModule, HoursEditor],
  templateUrl: './wizard.page.html',
  styleUrl: './wizard.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WizardPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);

  readonly plano = input<string>();
  readonly corrigir = input<string>();

  protected readonly typeOptions = TYPE_OPTIONS;
  protected readonly toBrazilian = toBrazilian;
  protected readonly step = signal<1 | 2 | 3>(1);
  protected readonly correction = signal<string | null>(null); // tenant id being corrected
  protected readonly lastStep = computed(() => (this.correction() ? 2 : 3));
  protected readonly defaults = signal<WizardDefaults | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<Problem | null>(null);
  protected readonly fieldErrors = computed(() => this.error()?.errors ?? {});

  // Step 1
  protected readonly type = signal<EstablishmentType | null>(null);
  // Step 2
  protected name = '';
  protected contactName = '';
  protected phone = '';
  protected city = '';
  protected state = '';
  protected street = '';
  protected number = '';
  protected district = '';
  protected postalCode = '';
  protected timeZone = 'America/Sao_Paulo';
  protected readonly slug = signal('');
  protected slugEdited = false;
  protected readonly slugNote = signal<string | null>(null);
  protected readonly hours = signal<HoursInterval[]>([]);
  // Step 3
  protected readonly services = signal<ServiceChoice[]>([]);
  protected acceptTerms = false;

  async ngOnInit(): Promise<void> {
    try {
      const [me, defaults] = await Promise.all([this.session.ensureLoaded(), this.api.wizardDefaults()]);
      this.defaults.set(defaults);
      this.hours.set(defaults.hours);
      this.timeZone = defaults.timeZone;
      this.contactName = me.name ?? '';
      const open = await this.api.application();
      if (open && !(this.corrigir() && open.status === 'REJECTED')) {
        await this.router.navigate(['/cadastro', 'status']);
        return;
      }
      if (open) this.prefill(open);
    } catch (e) {
      this.error.set(e as Problem);
    }
  }

  private prefill(app: NonNullable<Awaited<ReturnType<ApiService['application']>>>): void {
    this.correction.set(app.tenantId);
    this.type.set(app.establishmentType);
    this.name = app.name;
    this.contactName = app.contactName ?? this.contactName;
    this.phone = app.phone ?? '';
    this.city = app.address.city ?? '';
    this.state = app.address.state ?? '';
    this.street = app.address.street ?? '';
    this.number = app.address.number ?? '';
    this.district = app.address.district ?? '';
    this.postalCode = app.address.postalCode ?? '';
    this.timeZone = app.timeZone;
    this.slug.set(app.slug);
    this.slugEdited = true;
    this.hours.set(app.hours);
    this.step.set(2);
  }

  protected async chooseType(type: EstablishmentType): Promise<void> {
    this.type.set(type);
    try {
      this.services.set(choicesFrom(await this.api.presets(type)));
    } catch (e) {
      this.error.set(e as Problem);
    }
    this.step.set(2);
  }

  /** Suggests the public address from the name until the owner edits it by hand. */
  protected async nameChanged(): Promise<void> {
    if (this.slugEdited || this.name.trim().length < 2) return;
    try {
      const s = await this.api.suggestSlug(this.name.trim());
      this.slug.set(s.slug);
      this.slugNote.set(s.available ? null : 'Este endereço já está em uso; escolha outro.');
    } catch {
      /* the API validates again on submit */
    }
  }

  protected async slugChanged(value: string): Promise<void> {
    this.slugEdited = true;
    this.slug.set(value.trim().toLowerCase());
    try {
      const s = await this.api.slugAvailability(this.slug());
      this.slugNote.set(!s.valid ? s.problem : s.available ? null : 'Este endereço já está em uso; escolha outro.');
    } catch {
      /* the API validates again on submit */
    }
  }

  protected next(): void {
    if (this.step() === 2 && (!this.name.trim() || !this.contactName.trim())) {
      this.error.set({ code: 'validation', title: 'Dados inválidos', detail: 'Preencha o nome do salão e o seu nome.' });
      return;
    }
    this.error.set(null);
    this.step.update((s) => (s < 3 ? ((s + 1) as 2 | 3) : s));
  }

  protected back(): void {
    this.error.set(null);
    this.step.update((s) => (s > 1 ? ((s - 1) as 1 | 2) : s));
  }

  protected toggle(choice: ServiceChoice): void {
    this.services.update((all) => all.map((c) => (c === choice ? { ...c, selected: !c.selected } : c)));
  }

  protected setPrice(choice: ServiceChoice, text: string): void {
    this.services.update((all) => all.map((c) => (c === choice ? { ...c, price: toDecimal(text) } : c)));
  }

  protected setDuration(choice: ServiceChoice, minutes: string): void {
    this.services.update((all) => all.map((c) => (c === choice ? { ...c, durationMinutes: Number(minutes) } : c)));
  }

  protected async submit(): Promise<void> {
    const defaults = this.defaults();
    if (!defaults || !this.type()) return;
    const request: OnboardingRequest = {
      planCode: this.plano() ?? subscribeIntent.read() ?? 'launch',
      establishmentType: this.type()!,
      name: this.name.trim(),
      phone: this.phone.trim() || null,
      address: {
        ...EMPTY_ADDRESS,
        street: this.street.trim() || null,
        number: this.number.trim() || null,
        district: this.district.trim() || null,
        city: this.city.trim() || null,
        state: this.state.trim().toUpperCase() || null,
        postalCode: this.postalCode.trim() || null,
      },
      timeZone: this.timeZone,
      slug: this.slug(),
      contactName: this.contactName.trim(),
      hours: this.hours(),
      services: this.services()
        .filter((c) => c.selected)
        .map(({ name, category, durationMinutes, price, priceType }) => ({ name, category, durationMinutes, price, priceType })),
      acceptTerms: this.acceptTerms,
      termsVersion: defaults.termsVersion,
    };
    this.busy.set(true);
    this.error.set(null);
    try {
      const correction = this.correction();
      if (correction) await this.api.resubmit(correction, request);
      else await this.api.submitOnboarding(request);
      subscribeIntent.clear();
      await this.session.load();
      await this.router.navigate(['/cadastro', 'status']);
    } catch (e) {
      const problem = e as Problem;
      this.error.set(problem);
      const first = Object.keys(problem.errors ?? {})[0];
      if (first) this.step.set(Math.min(stepOf(first), this.lastStep()) as 1 | 2 | 3);
    } finally {
      this.busy.set(false);
    }
  }

  protected hourMinutes = [15, 30, 45, 60, 75, 90, 120, 150, 180, 240, 300, 360, 480];
}
