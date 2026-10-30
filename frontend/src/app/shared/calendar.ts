import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

const WEEKDAYS = ['D', 'S', 'T', 'Q', 'Q', 'S', 'S'];

/**
 * Month calendar: only the days in `enabled` can be tapped (spec public-booking "Calendar days").
 * Dates are ISO strings ("2026-11-03") so the salon's own calendar day is never shifted by time zones.
 */
@Component({
  selector: 'app-calendar',
  template: `
    <div class="head">
      <button type="button" class="nav" (click)="monthChange.emit(-1)" [disabled]="!canGoBack()" aria-label="Mês anterior">‹</button>
      <strong class="title">{{ title() }}</strong>
      <button type="button" class="nav" (click)="monthChange.emit(1)" [disabled]="!canGoForward()" aria-label="Próximo mês">›</button>
    </div>
    <div class="grid" role="grid">
      @for (w of weekdays; track $index) { <span class="wd" aria-hidden="true">{{ w }}</span> }
      @for (cell of cells(); track $index) {
        @if (cell) {
          <button type="button" class="day" [class.selected]="cell === selected()" [disabled]="!enabled().includes(cell)"
            (click)="pick.emit(cell)" [attr.aria-label]="cell" [attr.aria-pressed]="cell === selected()">{{ +cell.slice(8) }}</button>
        } @else {
          <span></span>
        }
      }
    </div>
  `,
  styles: `
    .head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
    .nav { min-width: 44px; min-height: 44px; border: 0; background: none; font-size: 1.6rem; color: var(--accent); cursor: pointer; }
    .nav:disabled { color: var(--line); cursor: default; }
    .grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 4px; text-align: center; }
    .wd { font-size: .75rem; color: var(--muted); font-weight: 600; padding: 4px 0; }
    .day { min-height: 44px; border-radius: 10px; border: 1px solid var(--line); background: var(--surface); font: inherit; font-weight: 600;
      color: var(--ink); cursor: pointer; }
    .day:disabled { background: transparent; border-color: transparent; color: #c9c1c6; cursor: default; }
    .day.selected { background: var(--accent); border-color: var(--accent); color: var(--accent-ink); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Calendar {
  /** "2026-11" */
  readonly month = input.required<string>();
  readonly enabled = input<string[]>([]);
  readonly selected = input<string | null>(null);
  readonly minMonth = input<string | null>(null);
  readonly maxMonth = input<string | null>(null);
  readonly pick = output<string>();
  readonly monthChange = output<number>();

  protected readonly weekdays = WEEKDAYS;
  protected readonly canGoBack = computed(() => !this.minMonth() || this.month() > this.minMonth()!);
  protected readonly canGoForward = computed(() => !this.maxMonth() || this.month() < this.maxMonth()!);
  protected readonly title = computed(() => {
    const [y, m] = this.month().split('-').map(Number);
    const text = new Date(y, m - 1, 1).toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' });
    return text.charAt(0).toUpperCase() + text.slice(1); // "Outubro de 2026", not "Outubro De 2026"

  });
  protected readonly cells = computed(() => {
    const [y, m] = this.month().split('-').map(Number);
    const first = new Date(y, m - 1, 1).getDay();
    const days = new Date(y, m, 0).getDate();
    const cells: (string | null)[] = Array(first).fill(null);
    for (let d = 1; d <= days; d++) cells.push(`${this.month()}-${String(d).padStart(2, '0')}`);
    return cells;
  });
}

/** "2026-11" + delta months. */
export function addMonths(month: string, delta: number): string {
  const [y, m] = month.split('-').map(Number);
  const d = new Date(y, m - 1 + delta, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}
