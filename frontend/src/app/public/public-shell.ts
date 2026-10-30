import { isPlatformBrowser } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, PLATFORM_ID, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';

/** Signed-in state for public pages: always false while prerendering (there is no user at build time). */
export function signedInSignal() {
  const signedIn = signal(false);
  if (isPlatformBrowser(inject(PLATFORM_ID))) {
    inject(OidcSecurityService, { optional: true })?.isAuthenticated$.subscribe((r) => signedIn.set(r.isAuthenticated));
  }
  return signedIn;
}

@Component({
  selector: 'app-public-header',
  imports: [RouterLink],
  template: `
    <header class="pub-header">
      <a routerLink="/" class="brand" aria-label="Estilix, página inicial">Estilix</a>
      <nav>
        <a routerLink="/planos">Planos</a>
        @if (signedIn()) {
          <a routerLink="/entrar" class="btn">Abrir o Estilix</a>
        } @else {
          <a routerLink="/entrar" class="btn secondary">Entrar</a>
        }
      </nav>
    </header>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PublicHeader {
  protected readonly signedIn = signedInSignal();
}

@Component({
  selector: 'app-public-footer',
  imports: [RouterLink],
  template: `
    <footer class="pub-footer">
      <div class="pub-wrap">
        <strong>Estilix</strong>
        <span class="muted small">Gestão para salões de beleza e barbearias · Feito no Brasil</span>
        <a routerLink="/planos" class="small">Planos</a>
      </div>
    </footer>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PublicFooter {}
