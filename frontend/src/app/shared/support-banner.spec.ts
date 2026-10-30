import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { SupportAccess } from '../core/models';
import { SupportBanner } from './support-banner';

/** Task 8.4: the owner is told, with the reason, while support has temporary access. */
describe('SupportBanner', () => {
  function render(access: SupportAccess | null): HTMLElement {
    const fixture = TestBed.createComponent(SupportBanner);
    fixture.componentRef.setInput('access', access);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the notice and the reason while a session is active', () => {
    const el = render({ sessionId: 's1', reason: 'Ticket #4821, erro na equipe', expiresAt: '2026-10-29T15:30:00Z' });

    expect(el.textContent).toContain('O suporte está com acesso temporário aos dados');
    expect(el.textContent).toContain('Ticket #4821, erro na equipe');
    expect(el.querySelector('[role=status]')).not.toBeNull();
  });

  it('renders nothing without an active session', () => {
    expect(render(null).textContent?.trim()).toBe('');
  });
});
