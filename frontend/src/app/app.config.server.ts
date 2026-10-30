import { provideHttpClient, withFetch } from '@angular/common/http';
import { ApplicationConfig, mergeApplicationConfig } from '@angular/core';
import { provideServerRendering, withRoutes } from '@angular/ssr';
import { baseConfig } from './app.config';
import { serverRoutes } from './app.routes.server';

/** Build-time prerender of the public pages: no sign-in, no API calls. */
export const config: ApplicationConfig = mergeApplicationConfig(baseConfig, {
  providers: [provideHttpClient(withFetch()), provideServerRendering(withRoutes(serverRoutes))],
});
