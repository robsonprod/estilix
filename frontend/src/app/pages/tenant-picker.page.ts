import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { ESTABLISHMENT_LABEL, ROLE_LABEL } from '../core/models';
import { SessionService } from '../core/session.service';

/** Shown once for people who work in more than one salon; the choice is remembered on the device. */
@Component({
  selector: 'app-tenant-picker',
  template: `
    <div class="page">
      <h1>Qual salão você quer abrir?</h1>
      <p class="muted">Vamos lembrar da sua escolha neste aparelho.</p>
      <div class="stack" style="margin-top: 16px">
        @for (t of tenants(); track t.id) {
          <button class="card choice" (click)="choose(t.id)">
            <strong>{{ t.name }}</strong>
            <span class="muted small">{{ types[t.establishmentType] }} · {{ roles[t.role] }}</span>
          </button>
        }
      </div>
    </div>
  `,
  styles: `.choice { display: grid; gap: 4px; text-align: left; font: inherit; color: inherit; cursor: pointer; min-height: 64px; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TenantPickerPage implements OnInit {
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  protected readonly types = ESTABLISHMENT_LABEL;
  protected readonly roles = ROLE_LABEL;
  protected tenants = () => (this.session.me()?.tenants ?? []).filter((t) => t.status === 'ACTIVE');

  async ngOnInit(): Promise<void> {
    await this.session.ensureLoaded();
  }

  protected choose(id: string): void {
    this.session.open(id);
    void this.router.navigate(['/s', id]);
  }
}
