import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { SessionService } from '../core/session.service';

/**
 * Salon home: the booking link to share and what is coming next. Features that are not built yet
 * say so honestly instead of showing fake data.
 */
@Component({
  selector: 'app-tenant-home',
  imports: [RouterLink],
  template: `
    <div class="page">
      <h1>Olá{{ firstName() ? ', ' + firstName() : '' }}!</h1>
      <p class="muted">Painel de {{ tenantName() }}.</p>

      @if (link()) {
        <div class="card stack share">
          <strong>Link de agendamento do salão</strong>
          <p class="muted small">Coloque na bio do Instagram e mande no WhatsApp: a cliente marca sozinha, sem ligar.</p>
          <a class="link" [href]="link()" target="_blank" rel="noopener">{{ link() }}</a>
          <div class="row wrap">
            <button type="button" class="btn secondary" (click)="copy()">{{ copied() ? 'Copiado!' : 'Copiar link' }}</button>
            <a class="btn" routerLink="agenda">Abrir a agenda</a>
          </div>
        </div>
      }

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
    .share { margin-top: 16px; }
    .link { color: var(--accent); word-break: break-all; font-weight: 600; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TenantHomePage implements OnInit {
  private readonly session = inject(SessionService);
  private readonly api = inject(ApiService);
  protected readonly tenantName = computed(() => this.session.currentTenant()?.name ?? 'salão');
  protected readonly firstName = computed(() => this.session.me()?.name?.split(' ')[0] ?? '');
  protected readonly link = signal<string | null>(null);
  protected readonly copied = signal(false);
  protected readonly upcoming = [
    { title: 'Atendimentos', text: 'Registre “Ana · Corte · R$ 80” em três toques.' },
    { title: 'Caixa', text: 'Entradas, despesas, fechamento do dia e comissões.' },
    { title: 'Clientes para chamar', text: 'Quem passou do tempo de voltar, com mensagem pronta.' },
  ];

  async ngOnInit(): Promise<void> {
    try {
      const salon = await this.api.salon(this.session.currentTenantId()!);
      this.link.set(`${location.origin}/agendar/${salon.slug}`);
    } catch {
      /* the home still works without the link */
    }
  }

  protected async copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.link()!);
      this.copied.set(true);
    } catch {
      /* the link is on screen */
    }
  }
}
