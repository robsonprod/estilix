import { HttpClient, provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, LOCALE_ID, mergeApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideClientHydration } from '@angular/platform-browser';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { authInterceptor, provideAuth, StsConfigHttpLoader, StsConfigLoader, withAppInitializerAuthCheck } from 'angular-auth-oidc-client';
import { map } from 'rxjs';
import { routes } from './app.routes';

registerLocaleData(localePt);

/** Shared by the browser and by the build-time prerender of the public pages. */
export const baseConfig: ApplicationConfig = {
  providers: [
    { provide: LOCALE_ID, useValue: 'pt-BR' },
    provideRouter(routes, withComponentInputBinding()),
    // Hydration reuses the prerendered HTML of the public pages; no event replay (it needs an inline script, blocked by our CSP).
    provideClientHydration(),
  ],
};

/** Runtime settings from /config.json: the same build runs against the local mock issuer or Cognito. */
interface RuntimeConfig {
  authority: string;
  clientId: string;
  scope: string;
}

function authConfigLoader(http: HttpClient): StsConfigLoader {
  const origin = window.location.origin;
  return new StsConfigHttpLoader(
    http.get<RuntimeConfig>('/config.json').pipe(
      map((c) => ({
        authority: c.authority,
        clientId: c.clientId,
        scope: c.scope,
        responseType: 'code', // Authorization Code + PKCE
        redirectUrl: `${origin}/entrar`, // the public landing page never handles the callback
        postLoginRoute: '/entrar', // the library's default '/' would drop the user on the public landing page
        postLogoutRedirectUri: origin,
        silentRenew: true,
        useRefreshToken: true,
        renewTimeBeforeTokenExpiresInSeconds: 30,
        secureRoutes: ['/api/'], // the token is sent to our API only
      })),
    ),
  );
}

/** Browser only: sign-in exists only where there is a window and a user. */
export const appConfig: ApplicationConfig = mergeApplicationConfig(baseConfig, {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor()])),
    provideAuth(
      { loader: { provide: StsConfigLoader, useFactory: authConfigLoader, deps: [HttpClient] } },
      withAppInitializerAuthCheck(),
    ),
  ],
});
