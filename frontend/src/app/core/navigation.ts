import { Me, Permission } from './models';

/**
 * Where to go right after sign-in:
 * support → support area; an ACTIVE salon → open it (picker if several, last choice remembered);
 * a salon in analysis or rejected → its status screen; someone who clicked "Assinar" → the wizard;
 * nobody → explanation page with a way to sign up.
 */
export function landingFor(me: Me, rememberedTenantId: string | null, subscribeIntent: string | null = null): string[] {
  if (me.platformSupport) return ['/suporte'];
  const active = me.tenants.filter((t) => t.status === 'ACTIVE');
  if (active.length === 1) return ['/s', active[0].id];
  if (active.length > 1) {
    const remembered = active.find((t) => t.id === rememberedTenantId);
    return remembered ? ['/s', remembered.id] : ['/escolher-salao'];
  }
  if (me.tenants.some((t) => t.status === 'PENDING_APPROVAL' || t.status === 'REJECTED')) return ['/cadastro', 'status'];
  if (subscribeIntent) return ['/cadastro'];
  return ['/sem-salao'];
}

export interface MenuItem {
  label: string;
  path: string;
  icon: 'home' | 'team' | 'shield' | 'user' | 'store' | 'scissors' | 'people' | 'more' | 'calendar';
  /** Shown in the phone's bottom bar; the others live under "Mais". */
  primary: boolean;
}

/** The menu shows only what the role allows; the API enforces the same rules. */
export function menuFor(permissions: readonly Permission[]): MenuItem[] {
  const can = (p: Permission) => permissions.includes(p);
  const items: MenuItem[] = [{ label: 'Início', path: '', icon: 'home', primary: true }];
  if (can('VIEW_AGENDA')) items.push({ label: 'Agenda', path: 'agenda', icon: 'calendar', primary: true });
  if (can('VIEW_SERVICES')) items.push({ label: 'Serviços', path: 'servicos', icon: 'scissors', primary: true });
  if (can('VIEW_PROFESSIONALS')) items.push({ label: 'Profissionais', path: 'profissionais', icon: 'people', primary: !can('VIEW_AGENDA') });
  if (can('VIEW_SALON')) items.push({ label: 'Salão', path: 'salao', icon: 'store', primary: false });
  if (can('VIEW_MEMBERS')) items.push({ label: 'Equipe', path: 'equipe', icon: 'team', primary: false });
  if (can('VIEW_AUDIT') || can('VIEW_ACCESS_HISTORY')) items.push({ label: 'Acessos', path: 'acessos', icon: 'shield', primary: false });
  items.push({ label: 'Perfil', path: 'perfil', icon: 'user', primary: false });
  return items;
}

/** Phone bottom bar: primary items plus "Mais" when something is left out. */
export function bottomBarFor(items: MenuItem[]): MenuItem[] {
  const primary = items.filter((i) => i.primary);
  return primary.length < items.length ? [...primary, { label: 'Mais', path: 'mais', icon: 'more', primary: true }] : primary;
}

const INTENT_KEY = 'estilix.subscribe';

/** Remembers "Assinar" across the round trip to the sign-in page (tab-scoped). */
export const subscribeIntent = {
  save(plan: string): void {
    try {
      sessionStorage.setItem(INTENT_KEY, plan);
    } catch {
      /* private mode: the user lands on "sem salão", which links to the plans again */
    }
  },
  read(): string | null {
    try {
      return sessionStorage.getItem(INTENT_KEY);
    } catch {
      return null;
    }
  },
  clear(): void {
    try {
      sessionStorage.removeItem(INTENT_KEY);
    } catch {
      /* ignore */
    }
  },
};
