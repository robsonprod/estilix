import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CustomersApi } from '../../core/customers-api.service';
import { brl } from '../../core/money';
import { CustomerSummary, Problem } from '../../core/models';
import { SessionService } from '../../core/session.service';
import { lastVisitLabel } from './customer-labels';

/** "Clientes" (spec customers/customer-records "Customer list and search"). */
@Component({
  selector: 'app-customers',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page">
      <h1>Clientes</h1>
      <label class="search">
        <span class="sr-only">Buscar por nome ou celular</span>
        <input type="search" [ngModel]="q()" (ngModelChange)="search($event)" placeholder="Buscar por nome ou celular" autocomplete="off" />
      </label>
      @if (error(); as e) { <div class="alert error" role="alert">{{ e.detail }}</div> }
      <ul class="list card">
        @for (c of items(); track c.id) {
          <li>
            <a class="row link" [routerLink]="[c.id]">
              <div class="grow">
                <strong>{{ c.name }}</strong>
                <div class="muted small">{{ lastVisitLabel(c.lastVisit) }} · {{ c.visits }} {{ c.visits === 1 ? 'visita' : 'visitas' }}</div>
              </div>
              <span class="muted small">{{ brl(c.totalSpent) }}</span>
            </a>
          </li>
        } @empty {
          <li class="muted">{{ q().trim() ? 'Nenhuma cliente encontrada.' : 'As clientes aparecem aqui a partir do primeiro agendamento ou atendimento.' }}</li>
        }
      </ul>
      @if (hasMore()) { <button type="button" class="btn secondary" (click)="more()" [disabled]="busy()">Ver mais</button> }
    </div>
  `,
  styles: `
    .search input { width: 100%; margin: 8px 0 12px; }
    .link { color: inherit; text-decoration: none; gap: 12px; }
    .grow { flex: 1; min-width: 0; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomersPage implements OnInit {
  private readonly api = inject(CustomersApi);
  private readonly session = inject(SessionService);

  protected readonly brl = brl;
  protected readonly lastVisitLabel = lastVisitLabel;
  protected readonly q = signal('');
  protected readonly items = signal<CustomerSummary[]>([]);
  protected readonly hasMore = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<Problem | null>(null);
  private page = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;

  ngOnInit(): Promise<void> {
    return this.load(true);
  }

  protected search(q: string): void {
    this.q.set(q);
    clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.load(true), 250);
  }

  protected more(): Promise<void> {
    return this.load(false);
  }

  private async load(reset: boolean): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      this.page = reset ? 0 : this.page + 1;
      const result = await this.api.customers(this.session.currentTenantId()!, this.q(), this.page);
      this.items.set(reset ? result.items : [...this.items(), ...result.items]);
      this.hasMore.set(result.hasMore);
    } catch (e) {
      this.error.set(e as Problem);
    } finally {
      this.busy.set(false);
    }
  }
}
