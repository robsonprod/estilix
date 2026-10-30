import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { SessionService } from '../../core/session.service';
import { WizardPage } from './wizard.page';

/** Task 7.1: prefills, slug suggestion, one request at the end and API field errors. */
describe('WizardPage', () => {
  async function setup(submitResult: unknown = { tenantId: 't1' }) {
    const api = {
      wizardDefaults: vi.fn().mockResolvedValue({
        hours: [1, 2, 3, 4, 5, 6].map((d) => ({ weekday: d, start: '09:00', end: '19:00' })),
        timeZone: 'America/Sao_Paulo',
        timeZones: ['America/Sao_Paulo', 'America/Fortaleza'],
        termsVersion: 'v0-draft',
        termsText: 'Termos.',
      }),
      application: vi.fn().mockResolvedValue(null),
      presets: vi.fn().mockResolvedValue([
        { name: 'Corte masculino', category: 'Cabelo', durationMinutes: 30, price: '45.00', priceType: 'FIXED' },
        { name: 'Barba', category: 'Barba', durationMinutes: 30, price: '35.00', priceType: 'FIXED' },
      ]),
      suggestSlug: vi.fn().mockResolvedValue({ slug: 'barbearia-do-ze', valid: true, available: true, problem: null }),
      submitOnboarding: vi.fn().mockImplementation(() =>
        submitResult instanceof Error ? Promise.reject(submitResult) : Promise.resolve(submitResult),
      ),
    };
    const session = {
      ensureLoaded: vi.fn().mockResolvedValue({ userId: 'u1', name: 'José Ferreira', tenants: [], platformSupport: false }),
      load: vi.fn().mockResolvedValue({}),
    };
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: ApiService, useValue: api }, { provide: SessionService, useValue: session }],
    });
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(WizardPage);
    const settle = async () => {
      for (let i = 0; i < 4; i++) {
        await new Promise((r) => setTimeout(r, 0));
        fixture.detectChanges();
      }
    };
    fixture.detectChanges();
    await settle();
    const page = fixture.componentInstance as unknown as Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any
    return { fixture, page, api, settle, el: fixture.nativeElement as HTMLElement };
  }

  it('prefills hours Mon–Sat 09:00–19:00 and the owner name', async () => {
    const { page } = await setup();

    expect(page['hours']().length).toBe(6);
    expect(page['contactName']).toBe('José Ferreira');
  });

  it('loads the presets of the chosen type and suggests the slug from the name', async () => {
    const { page, api, settle, el } = await setup();

    await page['chooseType']('BARBERSHOP');
    page['name'] = 'Barbearia do Zé';
    await page['nameChanged']();
    await settle();

    expect(api.presets).toHaveBeenCalledWith('BARBERSHOP');
    expect(page['slug']()).toBe('barbearia-do-ze');
    expect(el.textContent).toContain('Dados do salão');
  });

  it('sends one request with only the checked services', async () => {
    const { page, api } = await setup();
    await page['chooseType']('BARBERSHOP');
    page['name'] = 'Barbearia do Zé';
    page['slug'].set('barbearia-do-ze');
    page['toggle'](page['services']()[1]); // uncheck "Barba"
    page['acceptTerms'] = true;

    await page['submit']();

    const body = api.submitOnboarding.mock.calls[0][0];
    expect(body.services.map((s: { name: string }) => s.name)).toEqual(['Corte masculino']);
    expect(body.hours.length).toBe(6);
    expect(body.termsVersion).toBe('v0-draft');
  });

  it('shows API field errors and jumps back to their step', async () => {
    const problem = { code: 'validation', title: 'Dados inválidos', detail: 'Confira', errors: { slug: 'este endereço é reservado' } };
    const { page, settle, el } = await setup();
    (TestBed.inject(ApiService) as unknown as { submitOnboarding: ReturnType<typeof vi.fn> }).submitOnboarding.mockRejectedValue(problem);
    await page['chooseType']('SALON');
    page['step'].set(3);
    page['acceptTerms'] = true;

    await page['submit']();
    await settle();

    expect(page['step']()).toBe(2);
    expect(el.textContent).toContain('este endereço é reservado');
  });
});
