import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';

@Component({
  selector: 'app-no-tenant',
  imports: [RouterLink],
  template: `
    <div class="center-screen">
      <div class="card stack" style="max-width: 440px">
        <h1>Você ainda não tem um salão no Estilix</h1>
        <p class="muted">É dono de um salão ou barbearia? Cadastre o seu em 3 telas, grátis durante o lançamento.</p>
        <a class="btn" routerLink="/planos">Cadastrar meu salão</a>
        <p class="muted small">Trabalha em um salão que já usa o Estilix? Peça para o dono adicionar você à equipe usando o seu e-mail de login.</p>
        <button class="btn secondary" (click)="logout()">Sair</button>
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NoTenantPage {
  private readonly oidc = inject(OidcSecurityService);

  protected logout(): void {
    this.oidc.logoffAndRevokeTokens().subscribe();
  }
}
