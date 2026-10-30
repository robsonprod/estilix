import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { Address, EMPTY_ADDRESS, HoursInterval, Problem, SalonProfile } from '../../core/models';
import { SessionService } from '../../core/session.service';
import { HoursEditor } from '../../shared/hours-editor';

const TIME_ZONES = [
  'America/Sao_Paulo', 'America/Bahia', 'America/Fortaleza', 'America/Recife', 'America/Maceio', 'America/Belem',
  'America/Santarem', 'America/Araguaina', 'America/Manaus', 'America/Cuiaba', 'America/Campo_Grande',
  'America/Porto_Velho', 'America/Boa_Vista', 'America/Rio_Branco', 'America/Eirunepe', 'America/Noronha',
];

/** "Salão": profile and opening hours. Owners edit; everyone else reads. */
@Component({
  selector: 'app-salon',
  imports: [FormsModule, HoursEditor],
  template: `
    <div class="page">
      <h1>Salão</h1>
      @if (error(); as e) { <div class="alert error" role="alert">{{ e.detail }}</div> }
      @if (notice()) { <div class="alert ok" role="status">{{ notice() }}</div> }

      @if (profile(); as p) {
        <form class="card stack" (ngSubmit)="saveProfile()">
          <fieldset [disabled]="!canManage()" class="stack">
            <label>Nome <input name="name" [(ngModel)]="p.name" maxlength="120" /></label>
            @if (fieldErrors()['name']) { <span class="field-error">{{ fieldErrors()['name'] }}</span> }
            <label>Telefone <input name="phone" [(ngModel)]="p.phone" inputmode="tel" /></label>
            @if (fieldErrors()['phone']) { <span class="field-error">{{ fieldErrors()['phone'] }}</span> }
            <label>Endereço no link de agendamento <input name="slug" [(ngModel)]="p.slug" autocapitalize="off" /></label>
            @if (fieldErrors()['slug']) { <span class="field-error">{{ fieldErrors()['slug'] }}</span> }
            <div class="row two">
              <label>Rua <input name="street" [(ngModel)]="address.street" /></label>
              <label class="narrow">Nº <input name="number" [(ngModel)]="address.number" /></label>
            </div>
            <div class="row two">
              <label>Bairro <input name="district" [(ngModel)]="address.district" /></label>
              <label class="narrow">CEP <input name="postalCode" [(ngModel)]="address.postalCode" inputmode="numeric" /></label>
            </div>
            <div class="row two">
              <label>Cidade <input name="city" [(ngModel)]="address.city" /></label>
              <label class="narrow">UF <input name="state" [(ngModel)]="address.state" maxlength="2" /></label>
            </div>
            <label>Fuso horário
              <select name="timeZone" [(ngModel)]="p.timeZone">
                @for (tz of timeZones; track tz) { <option [value]="tz">{{ tz.replace('America/', '').replace('_', ' ') }}</option> }
              </select>
            </label>
          </fieldset>
          @if (canManage()) { <button class="btn" type="submit" [disabled]="busy()">Salvar dados</button> }
        </form>

        <h2>Horário de funcionamento</h2>
        <div class="card stack">
          @if (canManage()) {
            <app-hours-editor [(intervals)]="hours" [errors]="fieldErrors()" />
            <button class="btn" (click)="saveHours()" [disabled]="busy()">Salvar horários</button>
          } @else {
            <ul class="list">
              @for (h of hours(); track $index) { <li>{{ days[h.weekday - 1] }}: {{ h.start }}–{{ h.end }}</li> }
            </ul>
          }
        </div>
      }
    </div>
  `,
  styles: `.row.two { align-items: flex-start; } .row.two > label { flex: 1; } .narrow { max-width: 120px; }
    fieldset { border: 0; padding: 0; margin: 0; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SalonPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  protected readonly timeZones = TIME_ZONES;
  protected readonly days = ['Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado', 'Domingo'];
  protected readonly profile = signal<SalonProfile | null>(null);
  protected address: Address = { ...EMPTY_ADDRESS };
  protected readonly hours = signal<HoursInterval[]>([]);
  protected readonly error = signal<Problem | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly fieldErrors = computed(() => this.error()?.errors ?? {});
  protected readonly canManage = computed(() => this.session.permissions().includes('MANAGE_SALON'));

  private get tenantId(): string {
    return this.session.currentTenantId()!;
  }

  async ngOnInit(): Promise<void> {
    await this.run(async () => {
      const [profile, hours] = await Promise.all([this.api.salon(this.tenantId), this.api.salonHours(this.tenantId)]);
      this.profile.set(profile);
      this.address = { ...profile.address };
      this.hours.set(hours.intervals);
    });
  }

  protected saveProfile(): Promise<void> {
    const p = this.profile()!;
    return this.run(async () => {
      const state = this.address.state?.trim().toUpperCase() || null;
      this.profile.set(await this.api.updateSalon(this.tenantId, { name: p.name, phone: p.phone, slug: p.slug, timeZone: p.timeZone, address: { ...this.address, state } }));
      await this.session.load();
      this.notice.set('Dados do salão salvos.');
    });
  }

  protected saveHours(): Promise<void> {
    return this.run(async () => {
      this.hours.set((await this.api.replaceSalonHours(this.tenantId, this.hours())).intervals);
      this.notice.set('Horários salvos.');
    });
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
