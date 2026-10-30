import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { PriceType, Problem, ServiceItem } from '../../core/models';
import { SessionService } from '../../core/session.service';
import { toBrazilian, toDecimal } from '../onboarding/wizard-model';

interface Draft {
  id: string | null;
  name: string;
  category: string;
  durationMinutes: number;
  price: string;
  priceType: PriceType;
}

const EMPTY: Draft = { id: null, name: '', category: '', durationMinutes: 30, price: '', priceType: 'FIXED' };

/** "Serviços": by category, one short form to add or edit, inactive ones apart (spec service-catalog). */
@Component({
  selector: 'app-services',
  imports: [FormsModule],
  template: `
    <div class="page">
      <div class="row">
        <h1>Serviços</h1>
        <span class="spacer"></span>
        @if (canManage() && !draft()) { <button class="btn" (click)="edit(null)">Novo serviço</button> }
      </div>
      @if (error(); as e) { <div class="alert error" role="alert">{{ e.detail }}</div> }
      @if (notice()) { <div class="alert ok" role="status">{{ notice() }}</div> }

      @if (draft(); as d) {
        <form class="card stack" (ngSubmit)="save()">
          <strong>{{ d.id ? 'Editar serviço' : 'Novo serviço' }}</strong>
          <label>Nome <input name="name" [(ngModel)]="d.name" maxlength="80" /></label>
          @if (fieldErrors()['name']) { <span class="field-error">{{ fieldErrors()['name'] }}</span> }
          <label>Categoria <input name="category" [(ngModel)]="d.category" maxlength="40" list="categories" placeholder="Ex.: Cabelo" /></label>
          <datalist id="categories">@for (c of categories(); track c) { <option [value]="c"></option> }</datalist>
          @if (fieldErrors()['category']) { <span class="field-error">{{ fieldErrors()['category'] }}</span> }
          <div class="row two">
            <label>Duração
              <select name="duration" [(ngModel)]="d.durationMinutes">
                @for (m of minutes; track m) { <option [ngValue]="m">{{ m }} min</option> }
              </select>
            </label>
            <label>Preço (R$) <input name="price" [(ngModel)]="d.price" inputmode="decimal" placeholder="45,00" /></label>
          </div>
          @if (fieldErrors()['price']) { <span class="field-error">{{ fieldErrors()['price'] }}</span> }
          <div class="row">
            <label class="radio"><input type="radio" name="priceType" value="FIXED" [(ngModel)]="d.priceType" /> Preço fixo</label>
            <label class="radio"><input type="radio" name="priceType" value="FROM" [(ngModel)]="d.priceType" /> A partir de</label>
          </div>
          <div class="row">
            <button class="btn secondary" type="button" (click)="draft.set(null)">Cancelar</button>
            <span class="spacer"></span>
            <button class="btn" type="submit" [disabled]="busy()">Salvar</button>
          </div>
        </form>
      }

      @for (group of groups(); track group.category) {
        <h2>{{ group.category }}</h2>
        <ul class="list card">
          @for (s of group.items; track s.id) {
            <li class="row">
              <div class="grow">
                <strong>{{ s.name }}</strong>
                <div class="muted small">{{ s.durationMinutes }} min · {{ s.priceLabel }}</div>
              </div>
              @if (canManage()) {
                <button class="btn ghost" (click)="move(s, -1)" aria-label="Subir">↑</button>
                <button class="btn ghost" (click)="edit(s)">Editar</button>
                <button class="btn ghost" (click)="setActive(s, false)">Desativar</button>
              }
            </li>
          }
        </ul>
      } @empty {
        <div class="card stack">
          <p class="muted">Nenhum serviço ativo ainda.</p>
          @if (canManage()) { <button class="btn secondary" (click)="suggestions()">Adicionar sugestões</button> }
        </div>
      }

      @if (inactive().length) {
        <h2>Inativos</h2>
        <ul class="list card">
          @for (s of inactive(); track s.id) {
            <li class="row">
              <div class="grow muted"><strong>{{ s.name }}</strong> · {{ s.priceLabel }}</div>
              @if (canManage()) { <button class="btn ghost" (click)="setActive(s, true)">Reativar</button> }
            </li>
          }
        </ul>
      }

      @if (canManage() && groups().length) {
        <button class="btn ghost block" style="margin-top: 16px" (click)="suggestions()">Adicionar sugestões que faltam</button>
      }
    </div>
  `,
  styles: `.grow { flex: 1; min-width: 0; } .row.two { align-items: flex-start; } .row.two > label { flex: 1; }
    .radio { display: flex; gap: 8px; align-items: center; font-weight: 500; } .radio input { width: 20px; min-height: 20px; }
    li.row .btn.ghost { padding: 0 10px; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServicesPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  protected readonly minutes = [15, 30, 45, 60, 75, 90, 105, 120, 150, 180, 210, 240, 300, 360, 420, 480];
  protected readonly services = signal<ServiceItem[]>([]);
  protected readonly draft = signal<Draft | null>(null);
  protected readonly error = signal<Problem | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly fieldErrors = computed(() => this.error()?.errors ?? {});
  protected readonly canManage = computed(() => this.session.permissions().includes('MANAGE_SERVICES'));
  protected readonly inactive = computed(() => this.services().filter((s) => !s.active));
  protected readonly categories = computed(() => [...new Set(this.services().map((s) => s.category))]);
  protected readonly groups = computed(() => {
    const active = this.services().filter((s) => s.active);
    return [...new Set(active.map((s) => s.category))].map((category) => ({ category, items: active.filter((s) => s.category === category) }));
  });

  private get tenantId(): string {
    return this.session.currentTenantId()!;
  }

  async ngOnInit(): Promise<void> {
    await this.run(async () => this.services.set(await this.api.services(this.tenantId)));
  }

  protected edit(s: ServiceItem | null): void {
    this.error.set(null);
    this.draft.set(s ? { id: s.id, name: s.name, category: s.category, durationMinutes: s.durationMinutes, price: toBrazilian(s.price), priceType: s.priceType } : { ...EMPTY });
  }

  protected save(): Promise<void> {
    const d = this.draft()!;
    return this.run(async () => {
      await this.api.saveService(this.tenantId, d.id, {
        name: d.name.trim(), category: d.category.trim(), durationMinutes: Number(d.durationMinutes), price: toDecimal(d.price), priceType: d.priceType,
      });
      this.draft.set(null);
      this.services.set(await this.api.services(this.tenantId));
      this.notice.set('Serviço salvo.');
    });
  }

  protected setActive(s: ServiceItem, active: boolean): Promise<void> {
    return this.run(async () => {
      await this.api.setServiceActive(this.tenantId, s.id, active);
      this.services.set(await this.api.services(this.tenantId));
    });
  }

  /** Moves a service up inside the overall display order. */
  protected move(s: ServiceItem, delta: number): Promise<void> {
    const ids = this.services().filter((x) => x.active).map((x) => x.id);
    const i = ids.indexOf(s.id);
    if (i + delta < 0) return Promise.resolve();
    [ids[i], ids[i + delta]] = [ids[i + delta], ids[i]];
    return this.run(async () => this.services.set(await this.api.reorderServices(this.tenantId, ids)));
  }

  protected suggestions(): Promise<void> {
    return this.run(async () => {
      this.services.set(await this.api.addSuggestedServices(this.tenantId));
      this.notice.set('Sugestões adicionadas. Ajuste preços e durações se precisar.');
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
