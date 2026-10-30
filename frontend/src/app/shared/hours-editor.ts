import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { HoursInterval, WEEKDAYS } from '../core/models';

/**
 * Weekly hours with split shifts: each day has zero or more intervals (none = closed).
 * Times use 15-minute steps; the API validates overlaps and the grid again.
 */
@Component({
  selector: 'app-hours-editor',
  template: `
    <div class="days">
      @for (day of days; track day.weekday) {
        <div class="day">
          <div class="row">
            <strong class="name">{{ day.label }}</strong>
            <span class="spacer"></span>
            @if (of(day.weekday).length === 0) {
              <span class="muted small">Fechado</span>
            }
            <button type="button" class="btn ghost small-btn" (click)="add(day.weekday)" [attr.aria-label]="'Adicionar horário na ' + day.label">
              + horário
            </button>
          </div>
          @for (it of of(day.weekday); track $index) {
            <div class="row interval">
              <input type="time" step="900" [value]="it.start" (change)="set(it, 'start', $any($event.target).value)"
                [attr.aria-label]="day.label + ' início'" />
              <span class="muted">às</span>
              <input type="time" step="900" [value]="it.end" (change)="set(it, 'end', $any($event.target).value)"
                [attr.aria-label]="day.label + ' fim'" />
              <button type="button" class="btn ghost small-btn" (click)="remove(it)" [attr.aria-label]="'Remover horário de ' + day.label">✕</button>
            </div>
          }
        </div>
      }
    </div>
    @for (e of errorList(); track e) { <p class="field-error">{{ e }}</p> }
  `,
  styles: `
    .days { display: grid; gap: 8px; }
    .day { border-bottom: 1px solid var(--line); padding: 6px 0; }
    .name { min-width: 76px; }
    .interval { margin: 6px 0; }
    .interval input { width: auto; flex: 1; min-width: 0; }
    .small-btn { min-height: 36px; padding: 0 10px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HoursEditor {
  readonly intervals = model<HoursInterval[]>([]);
  /** Field errors from the API, keyed like `intervals[2]` or `hours[2]`. */
  readonly errors = input<Record<string, string>>({});

  protected readonly days = WEEKDAYS.map((label, i) => ({ label, weekday: i + 1 }));

  protected of(weekday: number): HoursInterval[] {
    return this.intervals().filter((i) => i.weekday === weekday);
  }

  protected add(weekday: number): void {
    const last = this.of(weekday).at(-1);
    const next = last ? { weekday, start: last.end, end: last.end < '18:00' ? '18:00' : '23:45' } : { weekday, start: '09:00', end: '18:00' };
    this.intervals.update((all) => [...all, next]);
  }

  protected remove(interval: HoursInterval): void {
    this.intervals.update((all) => all.filter((i) => i !== interval));
  }

  protected set(interval: HoursInterval, field: 'start' | 'end', value: string): void {
    this.intervals.update((all) => all.map((i) => (i === interval ? { ...i, [field]: value } : i)));
  }

  protected errorList(): string[] {
    const all = this.intervals();
    return Object.entries(this.errors())
      .filter(([k]) => /^(intervals|hours)\[\d+\]$/.test(k))
      .map(([k, msg]) => {
        const it = all[Number(k.match(/\d+/)![0])];
        return it ? `${WEEKDAYS[it.weekday - 1]} ${it.start}–${it.end}: ${msg}` : msg;
      });
  }
}
