import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { KeyValuePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CustomersApi } from '../../core/customers-api.service';
import { brl, toDecimal, toInput } from '../../core/money';
import { PAYMENT_LABEL, PaymentMethod, Problem, Professional, RecordItem, ServiceItem, ServiceRecord } from '../../core/models';
import { SessionService } from '../../core/session.service';

/**
 * The comanda (spec service-records/service-records): items with editable prices, "+ Serviço",
 * "Concluir" -> payment method (2 taps), and "Anular" for owners and managers.
 */
@Component({
  selector: 'app-record-sheet',
  imports: [FormsModule, KeyValuePipe],
  template: `
    @let r = record();
    <div class="stack sheet">
      <div class="row">
        <strong>Comanda · {{ r.customerName }}</strong>
        <span class="spacer"></span>
        <span class="badge" [class.ok]="r.status === 'CLOSED'" [class.neutral]="r.status === 'VOIDED'">{{ statusLabel() }}</span>
      </div>

      <ul class="list">
        @for (i of r.items; track i.id) {
          <li class="row item-row">
            <div class="grow">
              <div>{{ i.serviceName }}</div>
              <div class="muted small">{{ i.professionalName }}{{ i.fromBooking ? ' · agendado' : '' }}</div>
            </div>
            @if (editable(i)) {
              <label class="price">
                <span class="sr-only">Valor de {{ i.serviceName }}</span>
                <span class="prefix">R$</span>
                <input inputmode="decimal" [ngModel]="toInput(i.price)" (change)="savePrice(i, $any($event.target).value)" />
              </label>
              @if (r.items.length > 1) {
                <button type="button" class="btn ghost tiny" (click)="remove(i)" [attr.aria-label]="'Remover ' + i.serviceName">✕</button>
              }
            } @else {
              <span class="price-text">{{ brl(i.price) }}</span>
            }
          </li>
        }
      </ul>

      @if (r.status === 'OPEN' && canManage()) {
        @if (adding(); as a) {
          <div class="stack add">
            <div class="row two">
              <label>Serviço
                <select [ngModel]="a.serviceId" (ngModelChange)="adding.set({ serviceId: $event, professionalId: a.professionalId })">
                  @for (s of servicesOf(a.professionalId); track s.id) { <option [value]="s.id">{{ s.name }} · {{ s.priceLabel }}</option> }
                </select>
              </label>
              <label>Profissional
                <select [ngModel]="a.professionalId" (ngModelChange)="adding.set({ serviceId: servicesOf($event)[0]?.id ?? null, professionalId: $event })">
                  @for (p of pros(); track p.id) { <option [value]="p.id">{{ p.displayName }}</option> }
                </select>
              </label>
            </div>
            <div class="row">
              <button type="button" class="btn ghost" (click)="adding.set(null)">Voltar</button>
              <span class="spacer"></span>
              <button type="button" class="btn secondary" (click)="add()" [disabled]="busy() || !a.serviceId">Adicionar</button>
            </div>
          </div>
        } @else {
          <button type="button" class="btn ghost" (click)="startAdding()">+ Serviço</button>
        }
      }

      <div class="row total"><span>Total</span><span class="spacer"></span><strong>{{ brl(r.total) }}</strong></div>
      @if (r.paymentMethod) { <div class="muted small">Pago com {{ paymentLabel[r.paymentMethod] }}</div> }

      @if (error(); as e) {
        <div class="alert" [class.warn]="e.code === 'slot-taken'" [class.error]="e.code !== 'slot-taken'" role="alert">{{ e.detail }}</div>
        @if (e.errors; as errs) { @for (f of errs | keyvalue; track f.key) { <span class="field-error">{{ f.value }}</span> } }
      }

      @if (r.status === 'OPEN' && canManage()) {
        @if (paying()) {
          <p class="muted small">Como a cliente pagou?</p>
          <div class="pay">
            @for (m of methods; track m) {
              <button type="button" class="btn" (click)="close(m)" [disabled]="busy()">{{ paymentLabel[m] }}</button>
            }
          </div>
          <button type="button" class="btn ghost" (click)="paying.set(false)">Voltar</button>
        } @else {
          <button type="button" class="btn big" (click)="paying.set(true)" [disabled]="busy()">Concluir</button>
        }
      }

      @if (r.status !== 'VOIDED' && canVoid()) {
        <details>
          <summary>Anular comanda</summary>
          <div class="stack">
            <label>Motivo (só a equipe vê)
              <input [ngModel]="voidReason()" (ngModelChange)="voidReason.set($event)" maxlength="200" placeholder="Ex.: iniciei a cliente errada" />
            </label>
            <button type="button" class="btn danger" (click)="voidRecord()" [disabled]="busy() || voidReason().trim().length < 5">Anular</button>
          </div>
        </details>
      }
      <button type="button" class="btn ghost" (click)="dismissed.emit()">Fechar</button>
    </div>
  `,
  styles: `
    .item-row { gap: 8px; align-items: center; }
    .grow { flex: 1; min-width: 0; }
    .price { display: flex; align-items: center; gap: 4px; width: 120px; }
    .price input { width: 100%; text-align: right; }
    .prefix { color: var(--muted); font-size: .9rem; }
    .price-text { font-weight: 600; }
    .total { font-size: 1.1rem; }
    .pay { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .pay .btn, .big { min-height: 52px; font-size: 1.05rem; }
    .tiny { min-height: 32px; padding: 0 8px; }
    .row.two > label { flex: 1; }
    details summary { cursor: pointer; font-weight: 600; padding: 8px 0; color: var(--danger); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RecordSheet {
  private readonly records = inject(CustomersApi);
  private readonly session = inject(SessionService);

  readonly record = input.required<ServiceRecord>();
  readonly services = input<ServiceItem[]>([]);
  readonly pros = input<Professional[]>([]);
  /** Professional linked to a PROFESSIONAL user: they only add and edit their own items. */
  readonly ownProfessionalId = input<string | null>(null);
  readonly changed = output<ServiceRecord>();
  readonly dismissed = output<void>();

  protected readonly brl = brl;
  protected readonly toInput = toInput;
  protected readonly paymentLabel = PAYMENT_LABEL;
  protected readonly methods: PaymentMethod[] = ['PIX', 'CASH', 'DEBIT', 'CREDIT'];
  protected readonly paying = signal(false);
  protected readonly adding = signal<{ serviceId: string | null; professionalId: string } | null>(null);
  protected readonly voidReason = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<Problem | null>(null);

  protected readonly canManage = computed(() => this.session.permissions().includes('MANAGE_SERVICE_RECORDS'));
  protected readonly canVoid = computed(() => this.session.permissions().includes('VOID_SERVICE_RECORDS'));
  protected readonly statusLabel = computed(() => ({ OPEN: 'Em atendimento', CLOSED: 'Concluída', VOIDED: 'Anulada' })[this.record().status]);

  protected editable(item: RecordItem): boolean {
    const own = this.ownProfessionalId();
    return this.record().status === 'OPEN' && this.canManage() && (own === null || item.professionalId === own);
  }

  protected servicesOf(professionalId: string): ServiceItem[] {
    const ids = this.pros().find((p) => p.id === professionalId)?.serviceIds ?? [];
    return this.services().filter((s) => ids.includes(s.id));
  }

  protected startAdding(): void {
    const professionalId = this.ownProfessionalId() ?? this.record().items.at(-1)?.professionalId ?? this.pros()[0]?.id;
    if (!professionalId) return;
    this.adding.set({ serviceId: this.servicesOf(professionalId)[0]?.id ?? null, professionalId });
  }

  protected add(): Promise<void> {
    const a = this.adding();
    if (!a?.serviceId) return Promise.resolve();
    return this.run(async () => {
      this.changed.emit(await this.records.addItem(this.tenantId, this.record().id, { serviceId: a.serviceId!, professionalId: a.professionalId, price: null }));
      this.adding.set(null);
    });
  }

  protected savePrice(item: RecordItem, typed: string): Promise<void> {
    const price = toDecimal(typed);
    if (price === null) {
      this.error.set({ code: 'validation', title: 'Dados inválidos', detail: 'Valor inválido. Use, por exemplo, 45,00.' });
      return Promise.resolve();
    }
    if (price === item.price) return Promise.resolve();
    return this.run(async () => this.changed.emit(await this.records.updatePrice(this.tenantId, this.record().id, item.id, price)));
  }

  protected remove(item: RecordItem): Promise<void> {
    return this.run(async () => this.changed.emit(await this.records.removeItem(this.tenantId, this.record().id, item.id)));
  }

  protected close(method: PaymentMethod): Promise<void> {
    return this.run(async () => {
      this.changed.emit(await this.records.close(this.tenantId, this.record().id, method));
      this.paying.set(false);
    });
  }

  protected voidRecord(): Promise<void> {
    return this.run(async () => this.changed.emit(await this.records.void(this.tenantId, this.record().id, this.voidReason().trim())));
  }

  private get tenantId(): string {
    return this.session.currentTenantId()!;
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
