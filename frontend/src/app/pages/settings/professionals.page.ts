import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { HoursInterval, Member, Problem, Professional, ServiceItem, WEEKDAYS } from '../../core/models';
import { SessionService } from '../../core/session.service';
import { HoursEditor } from '../../shared/hours-editor';

interface Editing {
  id: string | null;
  displayName: string;
  color: string;
  userId: string | null;
  serviceIds: string[];
  ownHours: boolean;
  intervals: HoursInterval[];
  conflicts: HoursInterval[];
}

/** "Profissionais": who appears in the agenda, what they do and when (spec professionals). */
@Component({
  selector: 'app-professionals',
  imports: [FormsModule, HoursEditor],
  template: `
    <div class="page">
      <div class="row">
        <h1>Profissionais</h1>
        <span class="spacer"></span>
        @if (canManage() && !editing()) { <button class="btn" (click)="open(null)">Adicionar</button> }
      </div>
      <p class="muted">Quem aparece na agenda. Não precisa ter login no Estilix.</p>
      @if (error(); as e) { <div class="alert error" role="alert">{{ e.detail }}</div> }
      @if (notice()) { <div class="alert ok" role="status">{{ notice() }}</div> }

      @if (editing(); as e) {
        <div class="card stack">
          <strong>{{ e.id ? 'Editar profissional' : 'Novo profissional' }}</strong>
          <div class="row">
            <label class="grow">Nome na agenda <input [(ngModel)]="e.displayName" maxlength="60" /></label>
            <label class="color">Cor <input type="color" [(ngModel)]="e.color" /></label>
          </div>
          @if (fieldErrors()['displayName']) { <span class="field-error">{{ fieldErrors()['displayName'] }}</span> }
          @if (members().length) {
            <label>Login no Estilix (opcional)
              <select [(ngModel)]="e.userId">
                <option [ngValue]="null">Sem login</option>
                @for (m of members(); track m.userId) { <option [ngValue]="m.userId">{{ m.name ?? m.email }}</option> }
              </select>
            </label>
            @if (fieldErrors()['userId']) { <span class="field-error">{{ fieldErrors()['userId'] }}</span> }
          }

          @if (e.id) {
            <h2>Serviços que faz</h2>
            <div class="checks">
              @for (s of activeServices(); track s.id) {
                <label class="check"><input type="checkbox" [checked]="e.serviceIds.includes(s.id)" (change)="toggleService(e, s.id)" /> {{ s.name }}</label>
              } @empty {
                <p class="muted small">Cadastre serviços primeiro.</p>
              }
            </div>

            <h2>Horários</h2>
            <label class="check"><input type="checkbox" [(ngModel)]="e.ownHours" /> Horário próprio (senão segue o do salão)</label>
            @if (e.ownHours) {
              <app-hours-editor [(intervals)]="e.intervals" [errors]="fieldErrors()" />
            }
            @if (e.conflicts.length) {
              <div class="alert warn">Fora do horário do salão (não aparece na agenda):
                @for (c of e.conflicts; track $index) { {{ days[c.weekday - 1] }} {{ c.start }}–{{ c.end }}{{ $last ? '' : ',' }} }
              </div>
            }
          }

          <div class="row">
            <button class="btn secondary" (click)="editing.set(null)">Fechar</button>
            <span class="spacer"></span>
            <button class="btn" (click)="save(e)" [disabled]="busy()">Salvar</button>
          </div>
        </div>
      }

      <ul class="list card">
        @for (p of professionals(); track p.id) {
          <li class="row" [class.muted]="!p.active">
            <span class="dot" [style.background]="p.color"></span>
            <div class="grow">
              <strong>{{ p.displayName }}</strong>
              <div class="muted small">
                {{ p.serviceIds.length }} serviço(s) · {{ p.ownHours ? 'horário próprio' : 'horário do salão' }}{{ p.userId ? ' · com login' : '' }}{{ p.active ? '' : ' · inativo' }}
              </div>
            </div>
            @if (canManage()) {
              <button class="btn ghost" (click)="open(p)">Editar</button>
              <button class="btn ghost" (click)="setActive(p, !p.active)">{{ p.active ? 'Desativar' : 'Reativar' }}</button>
            }
          </li>
        } @empty {
          <li class="muted">Nenhum profissional cadastrado ainda.</li>
        }
      </ul>
    </div>
  `,
  styles: `.grow { flex: 1; min-width: 0; } .color { max-width: 80px; } .color input { padding: 4px; }
    .dot { width: 14px; height: 14px; border-radius: 50%; flex: none; }
    .checks { display: grid; gap: 8px; } .check { display: flex; gap: 10px; align-items: center; font-weight: 500; }
    .check input { width: 20px; min-height: 20px; flex: none; } li.row .btn.ghost { padding: 0 10px; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfessionalsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  protected readonly days = WEEKDAYS;
  protected readonly professionals = signal<Professional[]>([]);
  protected readonly services = signal<ServiceItem[]>([]);
  protected readonly members = signal<Member[]>([]);
  protected readonly editing = signal<Editing | null>(null);
  protected readonly error = signal<Problem | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly fieldErrors = computed(() => this.error()?.errors ?? {});
  protected readonly canManage = computed(() => this.session.permissions().includes('MANAGE_PROFESSIONALS'));
  protected readonly activeServices = computed(() => this.services().filter((s) => s.active));

  private get tenantId(): string {
    return this.session.currentTenantId()!;
  }

  async ngOnInit(): Promise<void> {
    await this.run(async () => {
      const canSeeMembers = this.session.permissions().includes('VIEW_MEMBERS');
      const [pros, services, members] = await Promise.all([
        this.api.professionals(this.tenantId),
        this.api.services(this.tenantId),
        canSeeMembers && this.canManage() ? this.api.members(this.tenantId) : Promise.resolve([]),
      ]);
      this.professionals.set(pros);
      this.services.set(services);
      this.members.set(members);
    });
  }

  protected async open(p: Professional | null): Promise<void> {
    this.error.set(null);
    if (!p) {
      this.editing.set({ id: null, displayName: '', color: '#8b2f5c', userId: null, serviceIds: [], ownHours: false, intervals: [], conflicts: [] });
      return;
    }
    await this.run(async () => {
      const hours = await this.api.professionalHours(this.tenantId, p.id);
      this.editing.set({
        id: p.id, displayName: p.displayName, color: p.color, userId: p.userId, serviceIds: [...p.serviceIds],
        ownHours: hours.ownHours, intervals: hours.ownHours ? hours.own : hours.effective, conflicts: hours.conflicts,
      });
    });
  }

  protected toggleService(e: Editing, id: string): void {
    e.serviceIds = e.serviceIds.includes(id) ? e.serviceIds.filter((x) => x !== id) : [...e.serviceIds, id];
  }

  /** Saves the profile; for an existing professional also the services and hours, in that order. */
  protected save(e: Editing): Promise<void> {
    return this.run(async () => {
      const saved = await this.api.saveProfessional(this.tenantId, e.id, { displayName: e.displayName.trim(), color: e.color, userId: e.userId });
      if (e.id) {
        await this.api.setProfessionalServices(this.tenantId, e.id, e.serviceIds);
        const hours = await this.api.replaceProfessionalHours(this.tenantId, e.id, e.ownHours, e.ownHours ? e.intervals : []);
        e.conflicts = hours.conflicts;
        this.editing.set(null);
        this.notice.set('Profissional salvo.');
      } else {
        this.notice.set('Profissional adicionado. Agora marque os serviços que essa pessoa faz.');
        this.professionals.set(await this.api.professionals(this.tenantId));
        await this.open(saved);
        return;
      }
      this.professionals.set(await this.api.professionals(this.tenantId));
    });
  }

  protected setActive(p: Professional, active: boolean): Promise<void> {
    return this.run(async () => {
      await this.api.setProfessionalActive(this.tenantId, p.id, active);
      this.professionals.set(await this.api.professionals(this.tenantId));
    });
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
