/** Mirrors the API contracts of the platform foundation. */

export type TenantRole = 'OWNER' | 'MANAGER' | 'PROFESSIONAL';
export type Permission =
  | 'VIEW_MEMBERS'
  | 'MANAGE_MEMBERS'
  | 'VIEW_AUDIT'
  | 'VIEW_ACCESS_HISTORY'
  | 'VIEW_SALON'
  | 'MANAGE_SALON'
  | 'VIEW_SERVICES'
  | 'MANAGE_SERVICES'
  | 'VIEW_PROFESSIONALS'
  | 'MANAGE_PROFESSIONALS';
export type EstablishmentType = 'SALON' | 'BARBERSHOP';
export type TenantStatus = 'PENDING_APPROVAL' | 'REJECTED' | 'ACTIVE' | 'SUSPENDED';

export interface SupportAccess {
  sessionId: string;
  reason: string;
  expiresAt: string;
}

export interface MyTenant {
  id: string;
  name: string;
  establishmentType: EstablishmentType;
  status: TenantStatus;
  role: TenantRole;
  permissions: Permission[];
  supportAccess: SupportAccess | null;
  professional: { id: string; displayName: string } | null;
}

export interface Me {
  userId: string;
  name: string | null;
  email: string | null;
  phone: string | null;
  platformSupport: boolean;
  tenants: MyTenant[];
}

export interface Member {
  userId: string;
  name: string | null;
  email: string | null;
  role: TenantRole;
  since: string;
}

export interface AuditEvent {
  id: string;
  occurredAt: string;
  actorUserId: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  details: Record<string, unknown>;
}

export interface AuditPage {
  page: number;
  size: number;
  items: AuditEvent[];
}

export interface BreakGlassSession {
  id: string;
  supportUserId: string;
  reason: string;
  startedAt: string;
  expiresAt: string;
  endedAt: string | null;
  status: 'ACTIVE' | 'ENDED' | 'EXPIRED';
  unmaskedReads: number;
}

export interface Tenant {
  id: string;
  name: string;
  establishmentType: EstablishmentType;
  status: TenantStatus;
  createdAt: string;
}

/** RFC 9457 body returned by the API. */
export interface Problem {
  code: string;
  title: string;
  detail: string;
  errors?: Record<string, string>;
  correlationId?: string;
}

export const ROLE_LABEL: Record<TenantRole, string> = {
  OWNER: 'Dono(a)',
  MANAGER: 'Gerente',
  PROFESSIONAL: 'Profissional',
};

export const ESTABLISHMENT_LABEL: Record<EstablishmentType, string> = {
  SALON: 'Salão de beleza',
  BARBERSHOP: 'Barbearia',
};

/** Salon setup (change salon-setup). Times are "HH:mm"; weekday is ISO (1 = Monday). */
export interface HoursInterval {
  weekday: number;
  start: string;
  end: string;
}

export interface Address {
  street: string | null;
  number: string | null;
  district: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;
}

export interface SalonProfile {
  id: string;
  name: string;
  establishmentType: EstablishmentType;
  status: TenantStatus;
  phone: string | null;
  address: Address;
  timeZone: string;
  slug: string;
}

export type SalonProfileInput = Pick<SalonProfile, 'name' | 'phone' | 'address' | 'timeZone' | 'slug'>;

export type PriceType = 'FIXED' | 'FROM';

export interface ServiceItem {
  id: string;
  name: string;
  category: string;
  durationMinutes: number;
  /** Decimal string, e.g. "45.00" — never a float. */
  price: string;
  priceType: PriceType;
  priceLabel: string;
  active: boolean;
  position: number;
}

export interface ServiceInput {
  name: string;
  category: string;
  durationMinutes: number;
  price: string;
  priceType: PriceType;
}

export interface Professional {
  id: string;
  displayName: string;
  userId: string | null;
  color: string;
  active: boolean;
  ownHours: boolean;
  serviceIds: string[];
}

export interface ProfessionalHours {
  ownHours: boolean;
  own: HoursInterval[];
  effective: HoursInterval[];
  conflicts: HoursInterval[];
}

export interface Plan {
  code: string;
  name: string;
  description: string;
  price: string;
  priceLabel: string;
  features: string[];
}

export interface SlugAvailability {
  slug: string;
  valid: boolean;
  available: boolean;
  problem: string | null;
}

export interface WizardDefaults {
  hours: HoursInterval[];
  timeZone: string;
  timeZones: string[];
  termsVersion: string;
  termsText: string;
}

export interface OnboardingRequest {
  planCode: string;
  establishmentType: EstablishmentType;
  name: string;
  phone: string | null;
  address: Address;
  timeZone: string;
  slug: string;
  contactName: string;
  hours: HoursInterval[];
  services: ServiceInput[];
  acceptTerms: boolean;
  termsVersion: string;
}

export interface Application {
  tenantId: string;
  name: string;
  establishmentType: EstablishmentType;
  status: TenantStatus;
  slug: string;
  phone: string | null;
  address: Address;
  timeZone: string;
  contactName: string | null;
  submittedAt: string | null;
  rejectionReason: string | null;
  hours: HoursInterval[];
  services: ServiceItem[];
}

export interface PendingSalon {
  tenantId: string;
  name: string;
  establishmentType: EstablishmentType;
  city: string | null;
  state: string | null;
  phone: string | null;
  contactName: string | null;
  contactEmail: string | null;
  planCode: string | null;
  termsVersion: string | null;
  termsAcceptedAt: string | null;
  submittedAt: string | null;
}

export const WEEKDAYS = ['Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado', 'Domingo'];

export const EMPTY_ADDRESS: Address = { street: null, number: null, district: null, city: null, state: null, postalCode: null };
