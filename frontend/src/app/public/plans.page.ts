import { isPlatformBrowser } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, PLATFORM_ID, signal } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { ApiService } from '../core/api.service';
import { Plan } from '../core/models';
import { subscribeIntent } from '../core/navigation';
import { Icon } from '../shared/icon';
import { FALLBACK_PLAN } from './features';
import { PublicFooter, PublicHeader, signedInSignal } from './public-shell';

/** Plans page: prerendered with the launch plan, refreshed from the catalog in the browser. */
@Component({
  selector: 'app-plans',
  imports: [Icon, PublicHeader, PublicFooter],
  template: `
    <app-public-header />
    <main class="pub-section">
      <div class="pub-wrap">
        <h1 class="section-title">Planos</h1>
        <p class="lead center">Sem cartão, sem fidelidade. Durante o lançamento, tudo é grátis.</p>
        <div class="plans">
          @for (p of plans(); track p.code) {
            <article class="card plan">
              <h2>{{ p.name }}</h2>
              <p class="price">{{ p.priceLabel }}</p>
              <p class="muted">{{ p.description }}</p>
              <ul class="list checks">
                @for (f of p.features; track f) {
                  @if (f.endsWith('(em breve)')) {
                    <li class="soon"><app-icon name="calendar" [size]="18" /> {{ f.replace(' (em breve)', '') }} <span class="badge neutral">Em breve</span></li>
                  } @else {
                    <li><app-icon name="check" [size]="18" /> {{ f }}</li>
                  }
                }
              </ul>
              <button class="btn block" (click)="subscribe(p.code)">Assinar</button>
            </article>
          }
        </div>
        <p class="muted small center">Os recursos marcados como “Em breve” entram no seu plano sem custo assim que forem lançados.</p>
      </div>
    </main>
    <app-public-footer />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlansPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly browser = isPlatformBrowser(inject(PLATFORM_ID));
  // providedIn: 'root' — injecting it (even optionally) would build it during the prerender, without sign-in config.
  private readonly oidc = this.browser ? inject(OidcSecurityService) : null;
  private readonly signedIn = signedInSignal();
  protected readonly plans = signal<Plan[]>([FALLBACK_PLAN]);

  constructor() {
    inject(Title).setTitle('Planos — Estilix');
  }

  async ngOnInit(): Promise<void> {
    if (!this.browser) return; // prerender: keep the built-in launch plan
    try {
      const plans = await this.api.plans();
      if (plans.length) this.plans.set(plans);
    } catch {
      /* keep the fallback plan */
    }
  }

  /** Free plan: no payment. Signed in → wizard; otherwise sign up/in first and come back to it. */
  protected subscribe(plan: string): void {
    subscribeIntent.save(plan);
    if (this.signedIn() || !this.oidc) {
      void this.router.navigate(['/cadastro'], { queryParams: { plano: plan } });
    } else {
      this.oidc.authorize();
    }
  }
}
