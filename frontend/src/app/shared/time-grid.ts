import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

/**
 * Times of a day: free ones in green, unavailable ones faded and disabled
 * (spec public-booking "Times of a day").
 */
@Component({
  selector: 'app-time-grid',
  template: `
    <div class="times" role="group" aria-label="Horários">
      @for (t of all(); track t.time) {
        <button type="button" class="time" [class.free]="t.free" [class.selected]="t.time === selected()" [disabled]="!t.free"
          (click)="pick.emit(t.time)" [attr.aria-label]="t.free ? t.time + ' livre' : t.time + ' indisponível'">{{ t.time }}</button>
      } @empty {
        <p class="muted">Nenhum horário neste dia.</p>
      }
    </div>
  `,
  styles: `
    .times { display: grid; grid-template-columns: repeat(auto-fill, minmax(72px, 1fr)); gap: 8px; }
    .time { min-height: 44px; border-radius: 10px; font: inherit; font-weight: 600; border: 1px solid transparent; cursor: pointer; }
    .time.free { background: var(--ok-soft); color: var(--ok); border-color: #bfe3cf; }
    .time:disabled { background: #f1eef2; color: #c2bac0; text-decoration: line-through; cursor: not-allowed; }
    .time.selected { background: var(--ok); color: #fff; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TimeGrid {
  readonly free = input<string[]>([]);
  readonly unavailable = input<string[]>([]);
  readonly selected = input<string | null>(null);
  readonly pick = output<string>();

  protected readonly all = computed(() =>
    [...this.free().map((time) => ({ time, free: true })), ...this.unavailable().map((time) => ({ time, free: false }))].sort((a, b) =>
      a.time.localeCompare(b.time),
    ),
  );
}
