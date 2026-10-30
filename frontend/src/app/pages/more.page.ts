import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { menuFor } from '../core/navigation';
import { SessionService } from '../core/session.service';
import { Icon } from '../shared/icon';

/** Phone-only "Mais": the menu items that do not fit in the bottom bar. */
@Component({
  selector: 'app-more',
  imports: [RouterLink, Icon],
  template: `
    <div class="page">
      <h1>Mais</h1>
      <ul class="list card">
        @for (item of items(); track item.path) {
          <li><a class="row link" [routerLink]="['..', item.path]"><app-icon [name]="item.icon" /> {{ item.label }}</a></li>
        }
      </ul>
    </div>
  `,
  styles: `.link { color: inherit; text-decoration: none; font-weight: 600; min-height: 32px; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MorePage {
  private readonly session = inject(SessionService);
  protected readonly items = computed(() => menuFor(this.session.permissions()).filter((i) => !i.primary));
}
