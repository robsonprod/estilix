import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Problem } from '../core/models';
import { landingFor, subscribeIntent } from '../core/navigation';
import { SessionService } from '../core/session.service';

/** `/entrar`: completes sign-in and decides where the user lands (see landingFor). */
@Component({
  selector: 'app-landing',
  template: `
    <div class="center-screen">
      @if (error(); as e) {
        <div class="card stack" style="max-width: 420px">
          <strong>{{ e.title }}</strong>
          <p class="muted">{{ e.detail }}</p>
          <button class="btn" (click)="go()">Tentar de novo</button>
        </div>
      } @else {
        <p class="muted" aria-live="polite">Abrindo o Estilix…</p>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LandingPage implements OnInit {
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  protected readonly error = signal<Problem | null>(null);

  ngOnInit(): void {
    void this.go();
  }

  protected async go(): Promise<void> {
    this.error.set(null);
    try {
      const me = await this.session.load();
      await this.router.navigate(landingFor(me, this.session.rememberedTenantId(), subscribeIntent.read()), { replaceUrl: true });
    } catch (e) {
      this.error.set(e as Problem);
    }
  }
}
