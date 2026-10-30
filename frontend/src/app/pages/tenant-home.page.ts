import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { SessionService } from '../core/session.service';

/**
 * Salon home. The business modules arrive in the next OpenSpec changes; the cards say so honestly
 * instead of showing fake data.
 */
@Component({
  selector: 'app-tenant-home',
  template: `
    <div class="page">
      <h1>Olá{{ firstName() ? ', ' + firstName() : '' }}!</h1>
      <p class="muted">Painel de {{ tenantName() }}.</p>
      <div class="grid">
        @for (m of upcoming; track m.title) {
          <div class="card">
            <span class="badge neutral">Em breve</span>
            <h2>{{ m.title }}</h2>
            <p class="muted small">{{ m.text }}</p>
          </div>
        }
      </div>
    </div>
  `,
  styles: `
    .grid { display: grid; gap: 12px; margin-top: 16px; }
    @media (min-width: 640px) { .grid { grid-template-columns: 1fr 1fr; } }
    h2 { margin: 10px 0 4px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TenantHomePage {
  private readonly session = inject(SessionService);
  protected readonly tenantName = computed(() => this.session.currentTenant()?.name ?? 'salão');
  protected readonly firstName = computed(() => this.session.me()?.name?.split(' ')[0] ?? '');
  protected readonly upcoming = [
    { title: 'Agenda', text: 'Clientes marcam pelo link do salão, sem ligar.' },
    { title: 'Atendimentos', text: 'Registre “Ana · Corte · R$ 80” em três toques.' },
    { title: 'Caixa', text: 'Entradas, despesas, fechamento do dia e comissões.' },
    { title: 'Clientes para chamar', text: 'Quem passou do tempo de voltar, com mensagem pronta.' },
  ];
}
