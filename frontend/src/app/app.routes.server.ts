import { RenderMode, ServerRoute } from '@angular/ssr';

/** Public pages become static HTML (fast, indexable); the signed-in app renders in the browser. */
export const serverRoutes: ServerRoute[] = [
  { path: '', renderMode: RenderMode.Prerender },
  { path: 'planos', renderMode: RenderMode.Prerender },
  { path: '**', renderMode: RenderMode.Client },
];
