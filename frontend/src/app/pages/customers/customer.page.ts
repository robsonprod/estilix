import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { KeyValuePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CustomersApi } from '../../core/customers-api.service';
import { brl } from '../../core/money';
import { CustomerDetail, PAYMENT_LABEL, Problem } from '../../core/models';
import { SessionService } from '../../core/session.service';
import { lastVisitLabel, usualIntervalLabel } from './customer-labels';

/**
 * One customer (spec customers/customer-records): the numbers that matter on top, then notes,
 * marketing consent and the visit history; "Privacidade" (export, anonymise) for owners only.
 */
@Component({
  selector: 'app-customer',
  imports: [FormsModule, KeyValuePipe, RouterLink],
  template: `
    <div class="page">
      <a routerLink=".." class="muted small">‹ Clientes</a>
      @if (error(); as e) {
        <div class="alert error" role="alert">{{ e.detail }}</div>
        @if (e.errors; as errs) { @for (f of errs | keyvalue; track f.key) { <span class="field-error">{{ f.value }}</span> } }
      }
      @if (notice()) { <div class="alert ok" role="status">{{ notice() }}</div> }

      @if (c(); as c) {
        <h1>{{ c.name }}</h1>
        @if (c.phone) { <a [href]="'tel:' + c.phone">{{ c.phone }}</a> }

        <div class="stats card">
          <div><strong>{{ c.visits }}</strong><span>{{ c.visits === 1 ? 'visita' : 'visitas' }}</span></div>
          <div><strong>{{ brl(c.totalSpent) }}</strong><span>gasto total</span></div>
          <div><strong>{{ c.noShows }}</strong><span>{{ c.noShows === 1 ? 'falta' : 'faltas' }}</span></div>
        </div>
        <p class="muted">{{ lastVisitLabel(c.lastVisit) }}@if (interval()) { · {{ interval() }} }</p>

        @if (!c.anonymised) {
          @if (canManage()) {
            <details class="card">
              <summary>Editar dados</summary>
              <div class="stack">
                <label>Nome <input [ngModel]="form().name" (ngModelChange)="patch({ name: $event })" maxlength="80" /></label>
                <label>Celular <input [ngModel]="form().phone" (ngModelChange)="patch({ phone: $event })" inputmode="tel" /></label>
                <label>Observações <small class="muted">(visível para a equipe)</small>
                  <textarea rows="3" [ngModel]="form().notes" (ngModelChange)="patch({ notes: $event })" maxlength="1000"></textarea>
                </label>
                <button type="button" class="btn" (click)="save()" [disabled]="busy()">Salvar</button>
              </div>
            </details>
          } @else if (c.notes) {
            <div class="card"><strong>Observações</strong> <small class="muted">(visível para a equipe)</small><p>{{ c.notes }}</p></div>
          }

          <div class="card stack">
            <strong>Mensagens de divulgação</strong>
            <span>{{ consentLabel() }}</span>
            @if (canConsent()) {
              <div class="row">
                <button type="button" class="btn secondary" (click)="consent('GRANTED')" [disabled]="busy() || c.marketingConsent === 'GRANTED'">Aceita receber</button>
                <button type="button" class="btn secondary" (click)="consent('REVOKED')" [disabled]="busy() || c.marketingConsent === 'REVOKED'">Não aceita</button>
              </div>
            }
            @if (c.consentHistory.length) {
              <ul class="muted small history">
                @for (h of c.consentHistory; track h.occurredAt) {
                  <li>{{ h.consent === 'GRANTED' ? 'Aceitou' : 'Recusou' }} em {{ date(h.occurredAt) }} ({{ h.source === 'STAFF' ? 'registrado pela equipe' : 'pela cliente' }})</li>
                }
              </ul>
            }
          </div>
        }

        <h2>Atendimentos</h2>
        <ul class="list card">
          @for (v of c.history; track v.recordId) {
            <li>
              <div class="row"><strong>{{ date(v.closedAt) }}</strong><span class="spacer"></span><strong>{{ brl(v.total) }}</strong></div>
              <div class="muted small">
                @for (i of v.items; track $index) { {{ i.serviceName }} com {{ i.professionalName }}{{ $last ? '' : ' · ' }} }
                · {{ paymentLabel[v.paymentMethod] }}
              </div>
            </li>
          } @empty {
            <li class="muted">Nenhum atendimento concluído ainda.</li>
          }
        </ul>

        @if (canDataRights() && !c.anonymised) {
          <details class="card privacy">
            <summary>Privacidade (LGPD)</summary>
            <div class="stack">
              <p class="small">A cliente pode pedir uma cópia dos dados dela ou pedir para ser apagada. Os valores dos atendimentos continuam nas contas do salão.</p>
              <button type="button" class="btn secondary" (click)="exportData()" [disabled]="busy()">Exportar dados</button>
              <label>Para anonimizar, digite ANONIMIZAR
                <input [ngModel]="confirmation()" (ngModelChange)="confirmation.set($event)" autocomplete="off" />
              </label>
              <button type="button" class="btn danger" (click)="anonymise()" [disabled]="busy() || confirmation().trim() !== 'ANONIMIZAR'">Anonimizar cliente</button>
            </div>
          </details>
        }
        @if (c.anonymised) { <p class="muted">Cliente anonimizada a pedido. Os valores continuam no histórico do salão.</p> }
      }
    </div>
  `,
  styles: `
    .stats { display: grid; grid-template-columns: repeat(3, 1fr); text-align: center; gap: 8px; margin: 12px 0 4px; }
    .stats div { display: flex; flex-direction: column; }
    .stats strong { font-size: 1.15rem; }
    .stats span { color: var(--muted); font-size: .8rem; }
    details summary { cursor: pointer; font-weight: 600; }
    .history { margin: 0; padding-left: 18px; }
    textarea { width: 100%; font: inherit; }
    .card { margin: 12px 0; }
    .privacy summary { color: var(--danger); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerPage implements OnInit {
  private readonly api = inject(CustomersApi);
  private readonly session = inject(SessionService);

  readonly customerId = input.required<string>();

  protected readonly brl = brl;
  protected readonly lastVisitLabel = lastVisitLabel;
  protected readonly paymentLabel = PAYMENT_LABEL;
  protected readonly c = signal<CustomerDetail | null>(null);
  protected readonly form = signal({ name: '', phone: '', notes: '' });
  protected readonly confirmation = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<Problem | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly canManage = computed(() => this.session.permissions().includes('MANAGE_CUSTOMERS'));
  protected readonly canConsent = computed(() => this.session.permissions().includes('MANAGE_MARKETING_CONSENT'));
  protected readonly canDataRights = computed(() => this.session.permissions().includes('CUSTOMER_DATA_RIGHTS'));
  protected readonly interval = computed(() => usualIntervalLabel(this.c()?.usualIntervalDays ?? null));
  protected readonly consentLabel = computed(() => {
    const c = this.c();
    if (!c) return '';
    return { UNKNOWN: 'Ainda não perguntado', GRANTED: 'Aceita receber mensagens', REVOKED: 'Não aceita receber mensagens' }[c.marketingConsent];
  });

  ngOnInit(): Promise<void> {
    return this.run(async () => this.show(await this.api.customer(this.tenantId, this.customerId())));
  }

  protected patch(changes: Partial<{ name: string; phone: string; notes: string }>): void {
    this.form.set({ ...this.form(), ...changes });
  }

  protected save(): Promise<void> {
    const f = this.form();
    return this.run(async () => {
      this.show(await this.api.updateCustomer(this.tenantId, this.customerId(), { name: f.name.trim(), phone: f.phone.trim(), notes: f.notes.trim() || null }));
      this.notice.set('Dados salvos.');
    });
  }

  protected consent(value: 'GRANTED' | 'REVOKED'): Promise<void> {
    return this.run(async () => this.show(await this.api.setConsent(this.tenantId, this.customerId(), value)));
  }

  protected exportData(): Promise<void> {
    return this.run(async () => {
      const blob = await this.api.exportCustomer(this.tenantId, this.customerId());
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `cliente-${this.customerId()}.json`;
      a.click();
      URL.revokeObjectURL(url);
      this.notice.set('Arquivo gerado. Entregue à cliente por um canal seguro.');
    });
  }

  protected anonymise(): Promise<void> {
    return this.run(async () => {
      await this.api.anonymise(this.tenantId, this.customerId(), this.confirmation().trim());
      this.show(await this.api.customer(this.tenantId, this.customerId()));
      this.notice.set('Cliente anonimizada.');
    });
  }

  protected date(iso: string): string {
    return new Date(iso).toLocaleDateString('pt-BR');
  }

  private show(c: CustomerDetail): void {
    this.c.set(c);
    this.form.set({ name: c.name, phone: c.phone ?? '', notes: c.notes ?? '' });
  }

  private get tenantId(): string {
    return this.session.currentTenantId()!;
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
