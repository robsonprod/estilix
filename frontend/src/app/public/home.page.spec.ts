import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { FEATURES } from './features';
import { HomePage } from './home.page';

/** Task 6.3: honest "Em breve" labels and the signed-in entry point (spec public-site). */
describe('HomePage', () => {
  function render(isAuthenticated: boolean): HTMLElement {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: OidcSecurityService, useValue: { isAuthenticated$: of({ isAuthenticated }) } }],
    });
    const fixture = TestBed.createComponent(HomePage);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('labels every feature that is not built yet as "Em breve"', () => {
    const el = render(false);
    const cards = Array.from(el.querySelectorAll('.feature'));

    FEATURES.forEach((f, i) => {
      expect(cards[i].textContent).toContain(f.title);
      expect(cards[i].textContent).toContain(f.status === 'soon' ? 'Em breve' : 'Disponível');
    });
    expect(FEATURES.find((f) => f.title.startsWith('Agenda'))?.status).toBe('soon');
  });

  it('offers "Entrar" to visitors and "Abrir o Estilix" to signed-in users', () => {
    expect(render(false).textContent).toContain('Entrar');
  });

  it('shows "Abrir o Estilix" when signed in', () => {
    expect(render(true).textContent).toContain('Abrir o Estilix');
  });
});
