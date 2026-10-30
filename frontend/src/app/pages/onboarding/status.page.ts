import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { ApiService } from '../../core/api.service';
import { Application, ESTABLISHMENT_LABEL, Problem, WEEKDAYS } from '../../core/models';

/** What the owner sees while the salon is in analysis or was rejected (spec tenant-onboarding). */
@Component({
  selector: 'app-onboarding-status',
  imports: [DatePipe, RouterLink],
  template: `
    <div class="page" style="max-width: 560px">
      @if (error(); as e) { <div class="alert error" role="alert">{{ e.detail }}</div> }
      @if (app(); as a) {
        @if (a.status === 'PENDING_APPROVAL') {
          <div class="card stack hero-card">
            <span class="badge warn">Em análise</span>
            <h1>Seu salão está em análise</h1>
            <p class="muted">
              A equipe do Estilix confere os dados e libera seu acesso. Se precisar, entraremos em contato pelo telefone informado.
              Assim que for liberado, é só entrar de novo.
            </p>
            <button class="btn secondary" (click)="refresh()">Verificar agora</button>
          </div>
        } @else {
          <div class="card stack hero-card">
            <span class="badge" style="background: var(--danger-soft); color: var(--danger)">Precisa de ajustes</span>
            <h1>Falta pouco</h1>
            <p><strong>Motivo:</strong> {{ a.rejectionReason }}</p>
            <a class="btn" routerLink="/cadastro" [queryParams]="{ corrigir: 1 }">Corrigir e reenviar</a>
          </div>
        }
        <h2>O que você enviou</h2>
        <div class="card stack">
          <div><strong>{{ a.name }}</strong> <span class="muted small">· {{ types[a.establishmentType] }}</span></div>
          <div class="muted small">{{ a.address.city }}{{ a.address.state ? ' / ' + a.address.state : '' }} · {{ a.phone }}</div>
          <div class="muted small">Enviado em {{ a.submittedAt | date: 'dd/MM/yyyy HH:mm' }}</div>
          <div>
            <strong class="small">Horários</strong>
            <ul class="plain small">
              @for (h of a.hours; track $index) { <li>{{ days[h.weekday - 1] }}: {{ h.start }}–{{ h.end }}</li> }
            </ul>
          </div>
          <div>
            <strong class="small">Serviços ({{ a.services.length }})</strong>
            <ul class="plain small">
              @for (s of a.services; track s.id) { <li>{{ s.name }} · {{ s.priceLabel }} · {{ s.durationMinutes }} min</li> }
            </ul>
          </div>
        </div>
      }
      <button class="btn ghost block" style="margin-top: 16px" (click)="logout()">Sair</button>
    </div>
  `,
  styles: `.hero-card { margin-bottom: 8px; } .plain { margin: 4px 0 0; padding-left: 18px; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly oidc = inject(OidcSecurityService);
  protected readonly types = ESTABLISHMENT_LABEL;
  protected readonly days = WEEKDAYS;
  protected readonly app = signal<Application | null>(null);
  protected readonly error = signal<Problem | null>(null);

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  /** No open application any more (approved, or nothing sent): let the landing logic decide. */
  protected async refresh(): Promise<void> {
    try {
      const app = await this.api.application();
      if (!app) {
        await this.router.navigate(['/entrar']);
        return;
      }
      this.app.set(app);
    } catch (e) {
      this.error.set(e as Problem);
    }
  }

  protected logout(): void {
    this.oidc.logoffAndRevokeTokens().subscribe();
  }
}
