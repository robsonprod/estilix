import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type IconName =
  | 'home' | 'team' | 'shield' | 'user' | 'switch' | 'logout' | 'alert'
  | 'store' | 'scissors' | 'people' | 'more' | 'check' | 'calendar' | 'cash' | 'phone' | 'lock';

const PATHS: Record<IconName, string> = {
  home: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
  team: 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-7 9a7 7 0 0 1 14 0M17 11a3 3 0 1 0 0-6M22 20a5 5 0 0 0-4-4.9',
  shield: 'M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z',
  user: 'M12 12a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9Zm-8 9a8 8 0 0 1 16 0',
  switch: 'M7 7h13l-4-4M17 17H4l4 4',
  logout: 'M15 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4M10 17l5-5-5-5M15 12H3',
  alert: 'M12 9v4m0 4h.01M10.3 3.9 2.5 17.5A2 2 0 0 0 4.2 20.5h15.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z',
  store: 'M4 10v10h16V10M3 4h18l-1.5 6H4.5zM10 20v-5h4v5',
  scissors: 'M6 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm0 12a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM8.1 7.9 20 20M8.1 16.1 20 4',
  people: 'M8 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm8 0a3.5 3.5 0 1 0 0-7M2 20a6 6 0 0 1 12 0M14.5 14.2A6 6 0 0 1 22 20',
  more: 'M5 12h.01M12 12h.01M19 12h.01',
  check: 'M4 12.5 9.5 18 20 6',
  calendar: 'M4 6h16v14H4zM4 10h16M8 3v4M16 3v4',
  cash: 'M3 7h18v10H3zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM6 10v4M18 10v4',
  phone: 'M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2',
  lock: 'M6 11h12v10H6zM8 11V7a4 4 0 0 1 8 0v4',
};

@Component({
  selector: 'app-icon',
  template: `<svg viewBox="0 0 24 24" [attr.width]="size()" [attr.height]="size()" fill="none" stroke="currentColor" stroke-width="1.8"
    stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path [attr.d]="d()" /></svg>`,
  styles: ':host { display: inline-flex; }',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Icon {
  readonly name = input.required<IconName>();
  readonly size = input(22);
  protected d = () => PATHS[this.name()];
}
