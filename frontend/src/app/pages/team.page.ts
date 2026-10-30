import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { Member, Problem, ROLE_LABEL, TenantRole } from '../core/models';
import { SessionService } from '../core/session.service';

/** "Equipe": everyone with VIEW_MEMBERS sees the team; only owners (MANAGE_MEMBERS) change it. */
@Component({
  selector: 'app-team',
  imports: [FormsModule],
  template: `
    <div class="page">
      <h1>Equipe</h1>
      <p class="muted">Quem trabalha no salão e o que cada pessoa pode fazer.</p>

      @if (error(); as e) { <div class="alert error" role="alert">{{ e.detail }}</div> }
      @if (notice()) { <div class="alert ok" role="status">{{ notice() }}</div> }

      @if (canManage()) {
        <form class="card stack add" (ngSubmit)="add()">
          <strong>Adicionar pessoa</strong>
          <label>E-mail de login
            <input type="email" name="email" [(ngModel)]="email" required autocomplete="off" inputmode="email" />
          </label>
          @if (fieldErrors()['email']) { <span class="field-error">{{ fieldErrors()['email'] }}</span> }
          <label>Papel
            <select name="role" [(ngModel)]="role">
              @for (r of roles; track r) { <option [value]="r">{{ roleLabel[r] }}</option> }
            </select>
          </label>
          <button class="btn" type="submit" [disabled]="busy() || !email">Adicionar</button>
        </form>
      }

      <ul class="list card" aria-label="Membros da equipe">
        @for (m of members(); track m.userId) {
          <li class="row wrap">
            <div class="who">
              <strong>{{ m.name ?? 'Sem nome' }}</strong>
              <span class="muted small">{{ m.email }}</span>
            </div>
            <span class="spacer"></span>
            @if (canManage()) {
              <select #roleSelect [attr.aria-label]="'Papel de ' + (m.name ?? m.email)" (change)="changeRole(m, roleSelect)">
                @for (r of roles; track r) { <option [value]="r" [selected]="r === m.role">{{ roleLabel[r] }}</option> }
              </select>
              <button class="btn danger" (click)="remove(m)" [disabled]="busy()">Remover</button>
            } @else {
              <span class="badge">{{ roleLabel[m.role] }}</span>
            }
          </li>
        } @empty {
          <li class="muted">Carregando…</li>
        }
      </ul>
    </div>
  `,
  styles: `
    .add { margin: 16px 0; }
    .who { display: grid; }
    select { width: auto; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TeamPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);

  protected readonly roles: TenantRole[] = ['OWNER', 'MANAGER', 'PROFESSIONAL'];
  protected readonly roleLabel = ROLE_LABEL;
  protected readonly members = signal<Member[]>([]);
  protected readonly error = signal<Problem | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly fieldErrors = computed(() => this.error()?.errors ?? {});
  protected readonly canManage = computed(() => this.session.permissions().includes('MANAGE_MEMBERS'));
  protected email = '';
  protected role: TenantRole = 'PROFESSIONAL';

  private get tenantId(): string {
    return this.session.currentTenantId()!;
  }

  async ngOnInit(): Promise<void> {
    await this.run(async () => this.members.set(await this.api.members(this.tenantId)));
  }

  protected add(): Promise<void> {
    return this.run(async () => {
      await this.api.addMember(this.tenantId, this.email.trim(), this.role);
      this.email = '';
      this.members.set(await this.api.members(this.tenantId));
      this.notice.set('Pessoa adicionada à equipe.');
    });
  }

  protected async changeRole(member: Member, select: HTMLSelectElement): Promise<void> {
    await this.run(async () => {
      await this.api.changeRole(this.tenantId, member.userId, select.value as TenantRole);
      this.notice.set('Papel atualizado.');
    }, true);
    // If the API refused (e.g. last owner), the reloaded role equals the old one and Angular has
    // nothing to re-render, so put the select back explicitly.
    const current = this.members().find((m) => m.userId === member.userId)?.role;
    if (current) select.value = current;
  }

  protected remove(member: Member): Promise<void> {
    if (!confirm(`Remover ${member.name ?? member.email} da equipe?`)) return Promise.resolve();
    return this.run(async () => {
      await this.api.removeMember(this.tenantId, member.userId);
      this.notice.set('Pessoa removida da equipe.');
    }, true);
  }

  /** Runs an action, showing the API's pt-BR message on failure; optionally reloads the list. */
  private async run(action: () => Promise<void>, reload = false): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(e as Problem);
      reload = true; // e.g. last-owner: put the select back to the real role
    } finally {
      if (reload) this.members.set(await this.api.members(this.tenantId).catch(() => this.members()));
      this.busy.set(false);
    }
  }
}
