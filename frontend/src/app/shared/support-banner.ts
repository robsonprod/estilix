import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { SupportAccess } from '../core/models';
import { Icon } from './icon';

/** Tells the owner, in plain words, that support can temporarily see personal data and why. */
@Component({
  selector: 'app-support-banner',
  imports: [DatePipe, Icon],
  template: `
    @if (access(); as a) {
      <div class="banner" role="status">
        <app-icon name="alert" />
        <div>
          <strong>O suporte está com acesso temporário aos dados</strong>
          <p>Motivo: “{{ a.reason }}” · até {{ a.expiresAt | date: 'HH:mm' }}. Tudo fica registrado em Acessos.</p>
        </div>
      </div>
    }
  `,
  styles: `
    .banner { display: flex; gap: 10px; align-items: flex-start; background: var(--warn-soft); color: var(--warn);
      padding: 12px 16px; border-bottom: 1px solid #f3dfb8; font-size: .92rem; }
    p { margin: 2px 0 0; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SupportBanner {
  readonly access = input<SupportAccess | null>(null);
}
