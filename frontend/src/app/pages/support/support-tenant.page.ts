import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { BreakGlassSession, Member, Problem, ROLE_LABEL } from '../../core/models';
import { SessionService } from '../../core/session.service';

export const MIN_REASON = 20;
export const MAX_MINUTES = 60;

/** Same rule the API enforces; checked here too so the button explains itself before submitting. */
export function breakGlassFormError(reason: string, minutes: number): string | null {
  if (reason.trim().length < MIN_REASON) return `Descreva o motivo com pelo menos ${MIN_REASON} caracteres.`;
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > MAX_MINUTES) return `Duração entre 1 e ${MAX_MINUTES} minutos.`;
  return null;
}

/** One salon seen by support: members masked, plus the break-glass control. */
@Component({
  selector: 'app-support-tenant',
  imports: [DatePipe, FormsModule, RouterLink],
  template: `
    <div class="page">
      <a routerLink="/suporte" class="btn ghost">← Todos os salões</a>
      @if (error(); as e) { <div class="alert error" role="alert">{{ e.detail }}</div> }

      @if (session(); as s) {
        <div class="alert warn" role="status">
          <strong>Acesso emergencial ativo até {{ s.expiresAt | date: 'HH:mm' }}.</strong>
          Você está vendo dados reais; cada consulta é registrada e o dono do salão vê este acesso.
          <div style="margin-top: 8px"><button class="btn secondary" (click)="end()">Encerrar agora</button></div>
        </div>
      } @else {
        <form class="card stack" (ngSubmit)="open()">
          <strong>Acesso emergencial</strong>
          <span class="muted small">Use só quando não der para resolver com dados mascarados. O dono do salão verá o motivo.</span>
          <label>Motivo
            <textarea name="reason" rows="3" [(ngModel)]="reason" placeholder="Ex.: ticket #4821, erro ao listar a equipe"></textarea>
          </label>
          <label>Duração (minutos)
            <input name="minutes" type="number" min="1" [max]="maxMinutes" [(ngModel)]="minutes" />
          </label>
          @if (formError()) { <span class="field-error">{{ formError() }}</span> }
          <button class="btn" type="submit" [disabled]="busy()">Abrir acesso</button>
        </form>
      }

      <h2>Equipe {{ session() ? '' : '(dados mascarados)' }}</h2>
      <ul class="list card">
        @for (m of members(); track m.userId) {
          <li class="row">
            <div class="who"><strong>{{ m.name }}</strong><span class="muted small">{{ m.email }}</span></div>
            <span class="spacer"></span>
            <span class="badge">{{ roles[m.role] }}</span>
          </li>
        }
      </ul>
    </div>
  `,
  styles: `.who { display: grid; } textarea { resize: vertical; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SupportTenantPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly me = inject(SessionService);
  readonly tenantId = input.required<string>();

  protected readonly roles = ROLE_LABEL;
  protected readonly maxMinutes = MAX_MINUTES;
  protected readonly members = signal<Member[]>([]);
  protected readonly session = signal<BreakGlassSession | null>(null);
  protected readonly error = signal<Problem | null>(null);
  protected readonly busy = signal(false);
  protected readonly submitted = signal(false);
  protected reason = '';
  protected minutes = 15;
  protected readonly formError = computed(() => (this.submitted() ? this.validation() : null));
  private readonly validation = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  protected async open(): Promise<void> {
    this.submitted.set(true);
    this.validation.set(breakGlassFormError(this.reason, Number(this.minutes)));
    if (this.validation()) return;
    await this.act(async () => {
      this.session.set(await this.api.openBreakGlass(this.tenantId(), this.reason.trim(), Number(this.minutes)));
      this.reason = '';
      this.submitted.set(false);
    });
  }

  protected async end(): Promise<void> {
    const s = this.session();
    if (!s) return;
    await this.act(async () => {
      await this.api.endBreakGlass(this.tenantId(), s.id);
      this.session.set(null);
    });
  }

  private async act(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
      await this.reload();
    } catch (e) {
      this.error.set(e as Problem);
    } finally {
      this.busy.set(false);
    }
  }

  private async reload(): Promise<void> {
    try {
      const me = await this.me.ensureLoaded();
      // After a page reload, pick up a session this support user already has open.
      const history = await this.api.accessHistory(this.tenantId());
      this.session.set(history.find((s) => s.status === 'ACTIVE' && s.supportUserId === me.userId) ?? null);
      this.members.set(await this.api.members(this.tenantId()));
    } catch (e) {
      this.error.set(e as Problem);
    }
  }
}
