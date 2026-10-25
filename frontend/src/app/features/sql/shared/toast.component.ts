import { Component, inject } from '@angular/core';
import { ToastService } from './toast.service';

/** Toast host: bottom-right, polite, no actions (DESIGN.md toast). */
@Component({
  selector: 'sql-toast',
  standalone: true,
  template: `
    <div class="toast-region" role="status" aria-live="polite">
      @if (toasts.current(); as t) {
        <div
          class="toast"
          [class.toast-warning]="t.kind === 'warning'"
          tabindex="-1"
          (mouseenter)="toasts.pause()"
          (mouseleave)="toasts.resume()"
          (focusin)="toasts.pause()"
          (focusout)="toasts.resume()"
        >
          {{ t.text }}
        </div>
      }
    </div>
  `,
  styles: `
    .toast-region { position: fixed; right: 16px; bottom: 16px; z-index: 900; }
    .toast {
      max-width: 420px; padding: 10px 14px; border-radius: 8px;
      background: var(--sql-surface-raised); color: var(--sql-text-primary);
      border: 1px solid var(--sql-border); border-left: 3px solid var(--sql-success);
      box-shadow: 0 8px 20px rgba(0, 0, 0, 0.4); font: 400 14px/1.5 var(--sql-font-ui);
      animation: sql-toast-in 120ms ease-out;
    }
    .toast-warning { border-left-color: var(--sql-warning); }
    @keyframes sql-toast-in { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
    @media (prefers-reduced-motion: reduce) { .toast { animation: none; } }
  `
})
export class ToastComponent {
  readonly toasts = inject(ToastService);
}
