import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { ApiService } from '../core/api.service';
import { Problem } from '../core/models';
import { SessionService } from '../core/session.service';

@Component({
  selector: 'app-profile',
  imports: [FormsModule],
  template: `
    <div class="page">
      <h1>Meu perfil</h1>
      <p class="muted">{{ email() }}</p>
      @if (error(); as e) { <div class="alert error" role="alert">{{ e.detail }}</div> }
      @if (saved()) { <div class="alert ok" role="status">Perfil salvo.</div> }
      <form class="card stack" (ngSubmit)="save()">
        <label>Nome
          <input name="name" [(ngModel)]="name" required maxlength="120" autocomplete="name" />
        </label>
        @if (fieldErrors()['name']) { <span class="field-error">{{ fieldErrors()['name'] }}</span> }
        <label>Telefone (opcional)
          <input name="phone" [(ngModel)]="phone" inputmode="tel" autocomplete="tel" placeholder="+55 85 99999-9999" />
        </label>
        @if (fieldErrors()['phone']) { <span class="field-error">{{ fieldErrors()['phone'] }}</span> }
        <button class="btn" type="submit" [disabled]="busy() || !name.trim()">Salvar</button>
      </form>
      <button class="btn secondary block" style="margin-top: 24px" (click)="logout()">Sair</button>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfilePage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  private readonly oidc = inject(OidcSecurityService);

  protected name = '';
  protected phone = '';
  protected readonly email = computed(() => this.session.me()?.email ?? '');
  protected readonly error = signal<Problem | null>(null);
  protected readonly saved = signal(false);
  protected readonly busy = signal(false);
  protected readonly fieldErrors = computed(() => this.error()?.errors ?? {});

  ngOnInit(): void {
    const me = this.session.me();
    this.name = me?.name ?? '';
    this.phone = me?.phone ?? '';
  }

  protected async save(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.saved.set(false);
    try {
      await this.api.updateProfile(this.name.trim(), this.phone.trim() || null);
      await this.session.load();
      this.saved.set(true);
    } catch (e) {
      this.error.set(e as Problem);
    } finally {
      this.busy.set(false);
    }
  }

  protected logout(): void {
    this.oidc.logoffAndRevokeTokens().subscribe();
  }
}
