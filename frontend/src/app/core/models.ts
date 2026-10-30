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
  | 'MANAGE_PROFESSIONALS'
  | 'VIEW_AGENDA'
  | 'MANAGE_AGENDA'
  | 'MANAGE_BOOKING_RULES'
  | 'VIEW_CUSTOMERS'
  | 'MANAGE_CUSTOMERS'
  | 'MANAGE_MARKETING_CONSENT'
  | 'CUSTOMER_DATA_RIGHTS'
  | 'VIEW_SERVICE_RECORDS'
  | 'MANAGE_SERVICE_RECORDS'
  | 'VOID_SERVICE_RECORDS';
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

/** Scheduling (change scheduling). Dates are "YYYY-MM-DD", times "HH:mm" in the salon's time zone. */
export interface PublicSalon {
  name: string;
  slug: string;
  phone: string | null;
  city: string | null;
  daysAhead: number;
  cancelNoticeMinutes: number;
  services: { id: string; name: string; category: string; durationMinutes: number; priceLabel: string }[];
  professionals: { id: string; displayName: string; serviceIds: string[] }[];
}

export interface DayTimes {
  date: string;
  free: string[];
  unavailable: string[];
}

export interface PublicBookingItem {
  id: string;
  serviceId: string;
  professionalId: string;
  serviceName: string;
  professionalName: string;
  date: string;
  start: string;
  end: string;
  status: BookingStatus;
  canChange: boolean;
}

export interface GroupView {
  salonName: string;
  salonSlug: string;
  salonPhone: string | null;
  cancelNoticeMinutes: number;
  bookings: PublicBookingItem[];
  code: string | null;
  token: string | null;
}

export interface BookingRules {
  daysAhead: number;
  minNoticeMinutes: number;
  cancelNoticeMinutes: number;
}

/** CONFIRMED, IN_SERVICE and DONE hold the time; NO_SHOW and CANCELLED free it (change customers-and-service-records). */
export type BookingStatus = 'CONFIRMED' | 'IN_SERVICE' | 'DONE' | 'NO_SHOW' | 'CANCELLED';

export const BOOKING_STATUS_LABEL: Record<BookingStatus, string> = {
  CONFIRMED: 'Confirmado',
  IN_SERVICE: 'Em atendimento',
  DONE: 'Concluído',
  NO_SHOW: 'Não veio',
  CANCELLED: 'Cancelado',
};

export interface AgendaItem {
  id: string;
  kind: 'BOOKING' | 'BLOCK';
  start: string;
  end: string;
  status: BookingStatus;
  serviceName: string | null;
  customerName: string | null;
  customerPhone: string | null;
  origin: 'PUBLIC' | 'STAFF' | 'WALK_IN' | null;
  blockReason: string | null;
  cancelledBy: 'CUSTOMER' | 'STAFF' | null;
  customerId?: string | null;
}

export interface AgendaColumn {
  professionalId: string;
  displayName: string;
  color: string;
  hours: HoursInterval[];
  items: AgendaItem[];
}

export interface AgendaDay {
  date: string;
  timeZone: string;
  professionals: AgendaColumn[];
  linkedProfessionalMissing: boolean;
}

// Customers and service records (change customers-and-service-records) ---------------------------

export type MarketingConsent = 'UNKNOWN' | 'GRANTED' | 'REVOKED';

export interface CustomerRef {
  id: string;
  name: string;
  phone: string | null;
}

export interface CustomerSummary {
  id: string;
  name: string;
  phone: string | null;
  visits: number;
  totalSpent: string;
  lastVisit: string | null;
  anonymised: boolean;
}

export interface CustomerPage {
  items: CustomerSummary[];
  page: number;
  hasMore: boolean;
}

export type PaymentMethod = 'PIX' | 'CASH' | 'DEBIT' | 'CREDIT';

export const PAYMENT_LABEL: Record<PaymentMethod, string> = { PIX: 'Pix', CASH: 'Dinheiro', DEBIT: 'Débito', CREDIT: 'Crédito' };

export interface Visit {
  recordId: string;
  closedAt: string;
  total: string;
  paymentMethod: PaymentMethod;
  items: { serviceName: string; professionalName: string; price: string }[];
}

export interface ConsentEvent {
  consent: MarketingConsent;
  source: 'STAFF' | 'CUSTOMER';
  actorUserId: string | null;
  occurredAt: string;
}

export interface CustomerDetail {
  id: string;
  name: string;
  phone: string | null;
  notes: string | null;
  marketingConsent: MarketingConsent;
  consentChangedAt: string | null;
  consentHistory: ConsentEvent[];
  visits: number;
  noShows: number;
  totalSpent: string;
  lastVisit: string | null;
  usualIntervalDays: number | null;
  history: Visit[];
  anonymised: boolean;
}

export type RecordStatus = 'OPEN' | 'CLOSED' | 'VOIDED';

export interface RecordItem {
  id: string;
  serviceId: string;
  serviceName: string;
  professionalId: string;
  professionalName: string;
  price: string;
  fromBooking: boolean;
  agendaEntryId: string | null;
}

export interface ServiceRecord {
  id: string;
  customerId: string;
  customerName: string;
  customerPhone: string | null;
  status: RecordStatus;
  paymentMethod: PaymentMethod | null;
  total: string;
  openedAt: string;
  closedAt: string | null;
  voidedAt: string | null;
  items: RecordItem[];
}

export interface DayRecords {
  date: string;
  records: ServiceRecord[];
  total: string;
}
