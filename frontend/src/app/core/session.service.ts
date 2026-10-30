import { computed, inject, Injectable, signal } from '@angular/core';
import { ApiService } from './api.service';
import { Me, MyTenant } from './models';

const LAST_TENANT_KEY = 'estilix.lastTenant';

/**
 * Who is signed in and which salon is open. Only the last chosen salon id is persisted
 * (localStorage, not personal data); everything else is reloaded from `/me`.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly api = inject(ApiService);

  readonly me = signal<Me | null>(null);
  readonly currentTenantId = signal<string | null>(null);
  readonly currentTenant = computed<MyTenant | null>(
    () => this.me()?.tenants.find((t) => t.id === this.currentTenantId()) ?? null,
  );
  readonly permissions = computed(() => this.currentTenant()?.permissions ?? []);

  async load(): Promise<Me> {
    const me = await this.api.me();
    this.me.set(me);
    return me;
  }

  async ensureLoaded(): Promise<Me> {
    return this.me() ?? this.load();
  }

  open(tenantId: string): void {
    this.currentTenantId.set(tenantId);
    try {
      localStorage.setItem(LAST_TENANT_KEY, tenantId);
    } catch {
      // private mode: the picker will simply show again next time
    }
  }

  rememberedTenantId(): string | null {
    try {
      return localStorage.getItem(LAST_TENANT_KEY);
    } catch {
      return null;
    }
  }
}
