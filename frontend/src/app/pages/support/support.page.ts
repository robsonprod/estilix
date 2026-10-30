import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { ApiService } from '../../core/api.service';
import { ESTABLISHMENT_LABEL, Problem, Tenant } from '../../core/models';

/** Platform support home: every salon, searchable. No personal data on this screen. */
@Component({
  selector: 'app-support',
  imports: [DatePipe, FormsModule, RouterLink],
  template: `
    <div class="page">
      <div class="row">
        <h1>Suporte Estilix</h1>
        <span class="spacer"></span>
        <button class="btn ghost" (click)="logout()">Sair</button>
      </div>
      <p class="muted">Você vê os dados pessoais mascarados. Para ver um dado real, abra um acesso emergencial no salão.</p>
      <a class="card row pending" routerLink="/suporte/aprovacoes">
        <strong>Salões aguardando liberação</strong>
        <span class="spacer"></span>
        <span class="badge" [class.warn]="pending() > 0">{{ pending() }}</span>
      </a>
      <label class="sr-only" for="q">Buscar salão</label>
      <input id="q" placeholder="Buscar salão pelo nome" [ngModel]="query()" (ngModelChange)="query.set($event)" />
      @if (error(); as e) { <div class="alert error" role="alert">{{ e.detail }}</div> }
      <ul class="list card" style="margin-top: 12px">
        @for (t of filtered(); track t.id) {
          <li>
            <a class="row link" [routerLink]="['/suporte', t.id]">
              <div class="who">
                <strong>{{ t.name }}</strong>
                <span class="muted small">{{ types[t.establishmentType] }} · desde {{ t.createdAt | date: 'MM/yyyy' }}</span>
              </div>
              <span class="spacer"></span>
              <span class="badge" [class.ok]="t.status === 'ACTIVE'" [class.warn]="t.status !== 'ACTIVE'">
                {{ statusLabel[t.status] }}
              </span>
            </a>
          </li>
        } @empty {
          <li class="muted">Nenhum salão encontrado.</li>
        }
      </ul>
    </div>
  `,
  styles: `.link { color: inherit; text-decoration: none; } .who { display: grid; }
    .pending { color: inherit; text-decoration: none; margin: 12px 0; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SupportPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly oidc = inject(OidcSecurityService);
  protected readonly types = ESTABLISHMENT_LABEL;
  protected readonly statusLabel: Record<Tenant['status'], string> = {
    ACTIVE: 'Ativo', SUSPENDED: 'Suspenso', PENDING_APPROVAL: 'Em análise', REJECTED: 'Recusado',
  };
  protected readonly tenants = signal<Tenant[]>([]);
  protected readonly query = signal('');
  protected readonly pending = computed(() => this.tenants().filter((t) => t.status === 'PENDING_APPROVAL').length);
  protected readonly error = signal<Problem | null>(null);
  protected readonly filtered = computed(() => {
    const q = this.query().trim().toLowerCase();
    return q ? this.tenants().filter((t) => t.name.toLowerCase().includes(q)) : this.tenants();
  });

  async ngOnInit(): Promise<void> {
    try {
      this.tenants.set(await this.api.supportTenants());
    } catch (e) {
      this.error.set(e as Problem);
    }
  }

  protected logout(): void {
    this.oidc.logoffAndRevokeTokens().subscribe();
  }
}
