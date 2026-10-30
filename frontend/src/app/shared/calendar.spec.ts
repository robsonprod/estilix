import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { addMonths, Calendar } from './calendar';
import { TimeGrid } from './time-grid';

/** Task 7.1: bookable days enabled, free times green, unavailable times faded and disabled. */
describe('Calendar', () => {
  function render(enabled: string[]) {
    const fixture = TestBed.createComponent(Calendar);
    fixture.componentRef.setInput('month', '2026-11');
    fixture.componentRef.setInput('enabled', enabled);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('only enabled days can be tapped', () => {
    const el = render(['2026-11-03', '2026-11-04']);
    const day = (n: string) => el.querySelector(`button[aria-label="2026-11-${n}"]`) as HTMLButtonElement;

    expect(day('03').disabled).toBe(false);
    expect(day('04').disabled).toBe(false);
    expect(day('05').disabled).toBe(true);
  });

  it('starts the month on the right weekday (Nov 1st 2026 is a Sunday)', () => {
    const el = render([]);
    const firstCell = el.querySelectorAll('.grid > *')[7];
    expect(firstCell.textContent?.trim()).toBe('1');
  });

  it('moves months across years', () => {
    expect(addMonths('2026-12', 1)).toBe('2027-01');
    expect(addMonths('2026-01', -1)).toBe('2025-12');
  });
});

describe('TimeGrid', () => {
  it('shows free times enabled and unavailable times disabled, in order', () => {
    const fixture = TestBed.createComponent(TimeGrid);
    fixture.componentRef.setInput('free', ['09:00', '10:30']);
    fixture.componentRef.setInput('unavailable', ['09:30', '10:00']);
    fixture.detectChanges();
    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')) as HTMLButtonElement[];

    expect(buttons.map((b) => b.textContent?.trim())).toEqual(['09:00', '09:30', '10:00', '10:30']);
    expect(buttons.map((b) => b.disabled)).toEqual([false, true, true, false]);
    expect(buttons[0].classList.contains('free')).toBe(true);
  });
});
