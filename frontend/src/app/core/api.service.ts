import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom, Observable } from 'rxjs';
import {
  Application,
  AuditPage,
  BreakGlassSession,
  EstablishmentType,
  HoursInterval,
  Me,
  Member,
  OnboardingRequest,
  PendingSalon,
  Plan,
  Problem,
  Professional,
  ProfessionalHours,
  SalonProfile,
  SalonProfileInput,
  ServiceInput,
  ServiceItem,
  SlugAvailability,
  Tenant,
  TenantRole,
  WizardDefaults,
} from './models';

/** Thin typed client of the Estilix API. All calls go to `/api` (proxied to the back-end). */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  me(): Promise<Me> {
    return this.call(this.http.get<Me>('/api/v1/me'));
  }

  updateProfile(name: string, phone: string | null): Promise<void> {
    return this.call(this.http.put<void>('/api/v1/me/profile', { name, phone }));
  }

  members(tenantId: string): Promise<Member[]> {
    return this.call(this.http.get<Member[]>(`/api/v1/tenants/${tenantId}/members`));
  }

  addMember(tenantId: string, email: string, role: TenantRole): Promise<Member> {
    return this.call(this.http.post<Member>(`/api/v1/tenants/${tenantId}/members`, { email, role }));
  }

  changeRole(tenantId: string, userId: string, role: TenantRole): Promise<void> {
    return this.call(this.http.put<void>(`/api/v1/tenants/${tenantId}/members/${userId}`, { role }));
  }

  removeMember(tenantId: string, userId: string): Promise<void> {
    return this.call(this.http.delete<void>(`/api/v1/tenants/${tenantId}/members/${userId}`));
  }

  auditEvents(tenantId: string, page = 0): Promise<AuditPage> {
    return this.call(this.http.get<AuditPage>(`/api/v1/tenants/${tenantId}/audit-events`, { params: { page, size: 50 } }));
  }

  accessHistory(tenantId: string): Promise<BreakGlassSession[]> {
    return this.call(this.http.get<BreakGlassSession[]>(`/api/v1/tenants/${tenantId}/access-history`));
  }

  supportTenants(): Promise<Tenant[]> {
    return this.call(this.http.get<Tenant[]>('/api/v1/support/tenants'));
  }

  openBreakGlass(tenantId: string, reason: string, minutes: number): Promise<BreakGlassSession> {
    return this.call(this.http.post<BreakGlassSession>(`/api/v1/support/tenants/${tenantId}/break-glass`, { reason, minutes }));
  }

  endBreakGlass(tenantId: string, sessionId: string): Promise<void> {
    return this.call(this.http.post<void>(`/api/v1/support/tenants/${tenantId}/break-glass/${sessionId}/end`, {}));
  }

  // Public (no login) -------------------------------------------------------------------------
  plans(): Promise<Plan[]> {
    return this.call(this.http.get<Plan[]>('/api/v1/public/plans'));
  }

  slugAvailability(slug: string): Promise<SlugAvailability> {
    return this.call(this.http.get<SlugAvailability>(`/api/v1/public/slugs/${encodeURIComponent(slug)}`));
  }

  suggestSlug(name: string): Promise<SlugAvailability> {
    return this.call(this.http.get<SlugAvailability>('/api/v1/public/slugs', { params: { name } }));
  }

  presets(type: EstablishmentType): Promise<ServiceInput[]> {
    return this.call(this.http.get<ServiceInput[]>('/api/v1/public/presets', { params: { type } }));
  }

  wizardDefaults(): Promise<WizardDefaults> {
    return this.call(this.http.get<WizardDefaults>('/api/v1/public/wizard-defaults'));
  }

  // Onboarding --------------------------------------------------------------------------------
  submitOnboarding(request: OnboardingRequest): Promise<{ tenantId: string }> {
    return this.call(this.http.post<{ tenantId: string }>('/api/v1/onboarding', request));
  }

  /** null when the person has no salon in analysis or rejected (HTTP 204). */
  application(): Promise<Application | null> {
    return this.call(this.http.get<Application | null>('/api/v1/onboarding/status'));
  }

  resubmit(tenantId: string, request: OnboardingRequest): Promise<{ tenantId: string }> {
    return this.call(this.http.put<{ tenantId: string }>(`/api/v1/onboarding/${tenantId}`, request));
  }

  approvals(): Promise<PendingSalon[]> {
    return this.call(this.http.get<PendingSalon[]>('/api/v1/support/approvals'));
  }

  approve(tenantId: string): Promise<void> {
    return this.call(this.http.post<void>(`/api/v1/support/approvals/${tenantId}/approve`, {}));
  }

  reject(tenantId: string, reason: string): Promise<void> {
    return this.call(this.http.post<void>(`/api/v1/support/approvals/${tenantId}/reject`, { reason }));
  }

  // Salon setup -------------------------------------------------------------------------------
  salon(tenantId: string): Promise<SalonProfile> {
    return this.call(this.http.get<SalonProfile>(`/api/v1/tenants/${tenantId}/salon`));
  }

  updateSalon(tenantId: string, profile: SalonProfileInput): Promise<SalonProfile> {
    return this.call(this.http.put<SalonProfile>(`/api/v1/tenants/${tenantId}/salon`, profile));
  }

  salonHours(tenantId: string): Promise<{ intervals: HoursInterval[] }> {
    return this.call(this.http.get<{ intervals: HoursInterval[] }>(`/api/v1/tenants/${tenantId}/salon/hours`));
  }

  replaceSalonHours(tenantId: string, intervals: HoursInterval[]): Promise<{ intervals: HoursInterval[] }> {
    return this.call(this.http.put<{ intervals: HoursInterval[] }>(`/api/v1/tenants/${tenantId}/salon/hours`, { intervals }));
  }

  services(tenantId: string): Promise<ServiceItem[]> {
    return this.call(this.http.get<ServiceItem[]>(`/api/v1/tenants/${tenantId}/services`));
  }

  saveService(tenantId: string, id: string | null, service: ServiceInput): Promise<ServiceItem> {
    const url = `/api/v1/tenants/${tenantId}/services`;
    return this.call(id ? this.http.put<ServiceItem>(`${url}/${id}`, service) : this.http.post<ServiceItem>(url, service));
  }

  setServiceActive(tenantId: string, id: string, active: boolean): Promise<ServiceItem> {
    return this.call(this.http.post<ServiceItem>(`/api/v1/tenants/${tenantId}/services/${id}/${active ? 'activate' : 'deactivate'}`, {}));
  }

  reorderServices(tenantId: string, ids: string[]): Promise<ServiceItem[]> {
    return this.call(this.http.put<ServiceItem[]>(`/api/v1/tenants/${tenantId}/services/order`, { ids }));
  }

  addSuggestedServices(tenantId: string): Promise<ServiceItem[]> {
    return this.call(this.http.post<ServiceItem[]>(`/api/v1/tenants/${tenantId}/services/suggestions`, {}));
  }

  professionals(tenantId: string): Promise<Professional[]> {
    return this.call(this.http.get<Professional[]>(`/api/v1/tenants/${tenantId}/professionals`));
  }

  saveProfessional(
    tenantId: string,
    id: string | null,
    body: { displayName: string; color: string | null; userId: string | null },
  ): Promise<Professional> {
    const url = `/api/v1/tenants/${tenantId}/professionals`;
    return this.call(id ? this.http.put<Professional>(`${url}/${id}`, body) : this.http.post<Professional>(url, body));
  }

  setProfessionalActive(tenantId: string, id: string, active: boolean): Promise<Professional> {
    return this.call(
      this.http.post<Professional>(`/api/v1/tenants/${tenantId}/professionals/${id}/${active ? 'activate' : 'deactivate'}`, {}),
    );
  }

  setProfessionalServices(tenantId: string, id: string, serviceIds: string[]): Promise<Professional> {
    return this.call(this.http.put<Professional>(`/api/v1/tenants/${tenantId}/professionals/${id}/services`, { serviceIds }));
  }

  professionalHours(tenantId: string, id: string): Promise<ProfessionalHours> {
    return this.call(this.http.get<ProfessionalHours>(`/api/v1/tenants/${tenantId}/professionals/${id}/hours`));
  }

  replaceProfessionalHours(tenantId: string, id: string, ownHours: boolean, intervals: HoursInterval[]): Promise<ProfessionalHours> {
    return this.call(
      this.http.put<ProfessionalHours>(`/api/v1/tenants/${tenantId}/professionals/${id}/hours`, { ownHours, intervals }),
    );
  }

  private async call<T>(request: Observable<T>): Promise<T> {
    try {
      return await firstValueFrom(request);
    } catch (error) {
      throw toProblem(error);
    }
  }
}

/** Turns any HTTP failure into a Problem with a pt-BR message the UI can show as is. */
export function toProblem(error: unknown): Problem {
  if (error instanceof HttpErrorResponse && error.error && typeof error.error === 'object' && 'code' in error.error) {
    return error.error as Problem;
  }
  if (error instanceof HttpErrorResponse && error.status === 0) {
    return { code: 'offline', title: 'Sem conexão', detail: 'Verifique sua internet e tente de novo.' };
  }
  return { code: 'internal', title: 'Erro inesperado', detail: 'Tente de novo em instantes.' };
}
