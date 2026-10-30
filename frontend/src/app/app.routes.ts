import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { autoLoginPartialRoutesGuard } from 'angular-auth-oidc-client';
import { SessionService } from './core/session.service';

/** Opens an ACTIVE salon of the URL, or sends the user back to the landing logic. */
const tenantGuard: CanActivateFn = async (route) => {
  const session = inject(SessionService);
  const me = await session.ensureLoaded();
  const id = route.paramMap.get('tenantId');
  if (id && me.tenants.some((t) => t.id === id && t.status === 'ACTIVE')) {
    session.open(id);
    return true;
  }
  return inject(Router).createUrlTree(['/entrar']);
};

const supportGuard: CanActivateFn = async () => {
  const me = await inject(SessionService).ensureLoaded();
  return me.platformSupport ? true : inject(Router).createUrlTree(['/entrar']);
};

export const routes: Routes = [
  // Public, prerendered, no sign-in (spec marketing/public-site).
  { path: '', pathMatch: 'full', loadComponent: () => import('./public/home.page').then((m) => m.HomePage) },
  { path: 'planos', loadComponent: () => import('./public/plans.page').then((m) => m.PlansPage) },
  // Public booking, no login (spec scheduling/public-booking).
  { path: 'agendar/:slug', loadComponent: () => import('./pages/booking/public-booking.page').then((m) => m.PublicBookingPage) },
  { path: 'agendamento/:token', loadComponent: () => import('./pages/booking/manage-booking.page').then((m) => m.ManageBookingPage) },

  // Everything else requires sign-in.
  {
    path: '',
    canActivateChild: [autoLoginPartialRoutesGuard],
    children: [
      // OIDC callback lands here, then the user is sent where they belong (landingFor).
      { path: 'entrar', loadComponent: () => import('./pages/landing.page').then((m) => m.LandingPage) },
      { path: 'cadastro', loadComponent: () => import('./pages/onboarding/wizard.page').then((m) => m.WizardPage) },
      { path: 'cadastro/status', loadComponent: () => import('./pages/onboarding/status.page').then((m) => m.StatusPage) },
      { path: 'escolher-salao', loadComponent: () => import('./pages/tenant-picker.page').then((m) => m.TenantPickerPage) },
      { path: 'sem-salao', loadComponent: () => import('./pages/no-tenant.page').then((m) => m.NoTenantPage) },
      {
        path: 's/:tenantId',
        canActivate: [tenantGuard],
        loadComponent: () => import('./layout/tenant-layout').then((m) => m.TenantLayout),
        children: [
          { path: '', loadComponent: () => import('./pages/tenant-home.page').then((m) => m.TenantHomePage) },
          { path: 'agenda', loadComponent: () => import('./pages/agenda/agenda.page').then((m) => m.AgendaPage) },
          { path: 'servicos', loadComponent: () => import('./pages/settings/services.page').then((m) => m.ServicesPage) },
          { path: 'profissionais', loadComponent: () => import('./pages/settings/professionals.page').then((m) => m.ProfessionalsPage) },
          { path: 'salao', loadComponent: () => import('./pages/settings/salon.page').then((m) => m.SalonPage) },
          { path: 'equipe', loadComponent: () => import('./pages/team.page').then((m) => m.TeamPage) },
          { path: 'acessos', loadComponent: () => import('./pages/access-history.page').then((m) => m.AccessHistoryPage) },
          { path: 'perfil', loadComponent: () => import('./pages/profile.page').then((m) => m.ProfilePage) },
          { path: 'mais', loadComponent: () => import('./pages/more.page').then((m) => m.MorePage) },
        ],
      },
      {
        path: 'suporte',
        canActivate: [supportGuard],
        loadComponent: () => import('./pages/support/support.page').then((m) => m.SupportPage),
      },
      {
        path: 'suporte/aprovacoes',
        canActivate: [supportGuard],
        loadComponent: () => import('./pages/support/approvals.page').then((m) => m.ApprovalsPage),
      },
      {
        path: 'suporte/:tenantId',
        canActivate: [supportGuard],
        loadComponent: () => import('./pages/support/support-tenant.page').then((m) => m.SupportTenantPage),
      },
      { path: '**', redirectTo: 'entrar' },
    ],
  },
];
