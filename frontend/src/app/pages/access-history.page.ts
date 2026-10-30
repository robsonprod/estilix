import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { ApiService } from '../core/api.service';
import { AuditEvent, BreakGlassSession, Problem } from '../core/models';
import { SessionService } from '../core/session.service';

const ACTION_LABEL: Record<string, string> = {
  USER_FIRST_SIGN_IN: 'Primeiro acesso de um usuário',
  MEMBERSHIP_ADDED: 'Pessoa adicionada à equipe',
  MEMBERSHIP_ROLE_CHANGED: 'Papel alterado',
  MEMBERSHIP_REMOVED: 'Pessoa removida da equipe',
  BREAK_GLASS_OPENED: 'Suporte abriu acesso emergencial',
  BREAK_GLASS_ENDED: 'Suporte encerrou o acesso emergencial',
  BREAK_GLASS_EXPIRED: 'Acesso emergencial expirou',
  UNMASKED_READ: 'Suporte visualizou dados pessoais',
};

const STATUS_LABEL: Record<BreakGlassSession['status'], string> = { ACTIVE: 'Ativo', ENDED: 'Encerrado', EXPIRED: 'Expirado' };

/** "Acessos": what platform support did in this salon and the security history (owners only). */
@Component({
  selector: 'app-access-history',
  imports: [DatePipe],
  template: `
    <div class="page">
      <h1>Acessos</h1>
      <p class="muted">Transparência: cada vez que o suporte do Estilix precisou ver dados do salão, aparece aqui.</p>
      @if (error(); as e) { <div class="alert error" role="alert">{{ e.detail }}</div> }

      @if (canSeeSessions()) {
        <h2>Acessos emergenciais do suporte</h2>
        <ul class="list card">
          @for (s of sessions(); track s.id) {
            <li class="stack">
              <div class="row">
                <strong>{{ s.startedAt | date: 'dd/MM/yyyy HH:mm' }}</strong>
                <span class="spacer"></span>
                <span class="badge" [class.warn]="s.status === 'ACTIVE'" [class.neutral]="s.status !== 'ACTIVE'">{{ statusLabel[s.status] }}</span>
              </div>
              <span>“{{ s.reason }}”</span>
              <span class="muted small">
                Até {{ (s.endedAt ?? s.expiresAt) | date: 'HH:mm' }} · {{ s.unmaskedReads }} consulta(s) com dados visíveis
              </span>
            </li>
          } @empty {
            <li class="muted">O suporte nunca precisou de acesso emergencial a este salão.</li>
          }
        </ul>
      }

      @if (canSeeAudit()) {
        <h2>Histórico de segurança</h2>
        <ul class="list card">
          @for (e of events(); track e.id) {
            <li class="row">
              <span>{{ label(e) }}</span>
              <span class="spacer"></span>
              <span class="muted small">{{ e.occurredAt | date: 'dd/MM HH:mm' }}</span>
            </li>
          } @empty {
            <li class="muted">Nenhum evento ainda.</li>
          }
        </ul>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccessHistoryPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);

  protected readonly statusLabel = STATUS_LABEL;
  protected readonly sessions = signal<BreakGlassSession[]>([]);
  protected readonly events = signal<AuditEvent[]>([]);
  protected readonly error = signal<Problem | null>(null);
  protected readonly canSeeSessions = computed(() => this.session.permissions().includes('VIEW_ACCESS_HISTORY'));
  protected readonly canSeeAudit = computed(() => this.session.permissions().includes('VIEW_AUDIT'));

  async ngOnInit(): Promise<void> {
    const id = this.session.currentTenantId()!;
    try {
      const [sessions, audit] = await Promise.all([
        this.canSeeSessions() ? this.api.accessHistory(id) : Promise.resolve([]),
        this.canSeeAudit() ? this.api.auditEvents(id) : Promise.resolve({ page: 0, size: 0, items: [] }),
      ]);
      this.sessions.set(sessions);
      this.events.set(audit.items);
    } catch (e) {
      this.error.set(e as Problem);
    }
  }

  protected label(e: AuditEvent): string {
    const base = ACTION_LABEL[e.action] ?? e.action;
    const to = e.details['to'];
    return typeof to === 'string' ? `${base} (agora ${to.toLowerCase()})` : base;
  }
}
