import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ESTABLISHMENT_LABEL } from '../core/models';
import { bottomBarFor, menuFor } from '../core/navigation';
import { SessionService } from '../core/session.service';
import { Icon } from '../shared/icon';
import { SupportBanner } from '../shared/support-banner';

/** Salon shell: full side menu on wide screens; on phones, up to 4 items plus "Mais" in the bottom bar. */
@Component({
  selector: 'app-tenant-layout',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon, SupportBanner],
  template: `
    @if (tenant(); as t) {
      <app-support-banner [access]="t.supportAccess" />
      <header class="top">
        <div>
          <div class="name">{{ t.name }}</div>
          <div class="muted small">{{ typeLabel() }}</div>
        </div>
        <span class="spacer"></span>
        @if (manyTenants()) {
          <a class="btn ghost" routerLink="/escolher-salao" aria-label="Trocar de salão"><app-icon name="switch" /></a>
        }
      </header>
      <div class="body">
        <nav class="side" aria-label="Menu principal">
          @for (item of menu(); track item.path) {
            <a [routerLink]="item.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: item.path === '' }">
              <app-icon [name]="item.icon" />
              <span>{{ item.label }}</span>
            </a>
          }
        </nav>
        <main><router-outlet /></main>
      </div>
      <nav class="bottom" aria-label="Menu rápido">
        @for (item of bottomBar(); track item.path) {
          <a [routerLink]="item.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: item.path === '' }">
            <app-icon [name]="item.icon" />
            <span>{{ item.label }}</span>
          </a>
        }
      </nav>
    }
  `,
  styles: `
    .top { display: flex; align-items: center; gap: 12px; padding: 14px 16px; background: var(--surface);
      border-bottom: 1px solid var(--line); position: sticky; top: 0; z-index: 2; }
    .name { font-weight: 700; font-size: 1.05rem; }
    .side { display: none; }
    .bottom { position: fixed; bottom: 0; left: 0; right: 0; height: var(--nav-height); display: flex;
      background: var(--surface); border-top: 1px solid var(--line); z-index: 3; padding-bottom: env(safe-area-inset-bottom); }
    .bottom a { flex: 1; display: grid; place-items: center; align-content: center; gap: 2px; color: var(--muted);
      text-decoration: none; font-size: .72rem; font-weight: 600; }
    .bottom a.active { color: var(--accent); }
    @media (min-width: 900px) {
      .bottom { display: none; }
      .body { display: grid; grid-template-columns: 220px 1fr; }
      .side { display: flex; position: sticky; top: 64px; height: calc(100dvh - 64px); flex-direction: column; gap: 4px;
        padding: 16px 12px; border-right: 1px solid var(--line); }
      .side a { display: flex; gap: 12px; align-items: center; padding: 10px 12px; border-radius: 10px; color: var(--muted);
        text-decoration: none; font-weight: 600; }
      .side a.active { background: var(--accent-soft); color: var(--accent); }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TenantLayout {
  private readonly session = inject(SessionService);
  protected readonly tenant = this.session.currentTenant;
  protected readonly menu = computed(() => menuFor(this.session.permissions()));
  protected readonly bottomBar = computed(() => bottomBarFor(this.menu()));
  protected readonly manyTenants = computed(() => (this.session.me()?.tenants.filter((t) => t.status === 'ACTIVE').length ?? 0) > 1);
  protected readonly typeLabel = computed(() => {
    const t = this.tenant();
    return t ? ESTABLISHMENT_LABEL[t.establishmentType] : '';
  });
}
