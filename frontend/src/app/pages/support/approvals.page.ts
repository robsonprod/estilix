import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { ESTABLISHMENT_LABEL, PendingSalon, Problem } from '../../core/models';

/**
 * Approval queue (spec tenant-onboarding). Contact data is shown in clear here on purpose: support
 * needs it to verify the salon before the contract, and only salons in analysis are listed.
 */
@Component({
  selector: 'app-approvals',
  imports: [DatePipe, FormsModule, RouterLink],
  template: `
    <div class="page">
      <a routerLink="/suporte" class="btn ghost">← Suporte</a>
      <h1>Salões aguardando liberação</h1>
      <p class="muted small">Os contatos abaixo aparecem sem máscara só para conferir o cadastro. Cada abertura desta fila fica registrada.</p>
      @if (error(); as e) { <div class="alert error" role="alert">{{ e.detail }}</div> }
      @if (notice()) { <div class="alert ok" role="status">{{ notice() }}</div> }

      @for (s of queue(); track s.tenantId) {
        <article class="card stack item">
          <div class="row">
            <strong>{{ s.name }}</strong>
            <span class="spacer"></span>
            <span class="badge">{{ types[s.establishmentType] }}</span>
          </div>
          <div class="small">{{ s.city }}{{ s.state ? ' / ' + s.state : '' }} · {{ s.phone ?? 'sem telefone' }}</div>
          <div class="small">Responsável: {{ s.contactName }} · {{ s.contactEmail }}</div>
          <div class="muted small">Enviado em {{ s.submittedAt | date: 'dd/MM HH:mm' }} · plano {{ s.planCode }} · termos {{ s.termsVersion }}</div>
          @if (rejecting() === s.tenantId) {
            <label>Motivo (a dona verá esta mensagem)
              <textarea rows="2" [(ngModel)]="reason" placeholder="Ex.: o telefone não atende, confirme o número"></textarea>
            </label>
            <div class="row">
              <button class="btn secondary" (click)="rejecting.set(null)">Cancelar</button>
              <span class="spacer"></span>
              <button class="btn danger" (click)="reject(s)" [disabled]="busy()">Recusar</button>
            </div>
          } @else {
            <div class="row">
              <button class="btn secondary" (click)="rejecting.set(s.tenantId); reason = ''">Recusar…</button>
              <span class="spacer"></span>
              <button class="btn" (click)="approve(s)" [disabled]="busy()">Aprovar</button>
            </div>
          }
        </article>
      } @empty {
        <div class="card"><p class="muted">Nenhum salão aguardando. 🎉</p></div>
      }
    </div>
  `,
  styles: `.item { margin-bottom: 12px; } textarea { resize: vertical; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ApprovalsPage implements OnInit {
  private readonly api = inject(ApiService);
  protected readonly types = ESTABLISHMENT_LABEL;
  protected readonly queue = signal<PendingSalon[]>([]);
  protected readonly rejecting = signal<string | null>(null);
  protected readonly error = signal<Problem | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected reason = '';

  async ngOnInit(): Promise<void> {
    await this.run(async () => this.queue.set(await this.api.approvals()));
  }

  protected approve(s: PendingSalon): Promise<void> {
    return this.run(async () => {
      await this.api.approve(s.tenantId);
      this.notice.set(`${s.name} foi liberado.`);
      this.queue.set(await this.api.approvals());
    });
  }

  protected reject(s: PendingSalon): Promise<void> {
    return this.run(async () => {
      await this.api.reject(s.tenantId, this.reason.trim());
      this.rejecting.set(null);
      this.notice.set(`${s.name} voltou para ajustes.`);
      this.queue.set(await this.api.approvals());
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
      if ((e as Problem).code === 'conflict') this.queue.set(await this.api.approvals().catch(() => this.queue()));
    } finally {
      this.busy.set(false);
    }
  }
}
