import { Injectable, signal } from '@angular/core';

export type ToastKind = 'success' | 'warning';

export interface Toast {
  id: number;
  kind: ToastKind;
  text: string;
}

export const TOAST_DURATION_MS = 5000;

/** One toast at a time (EXPERIENCE.md); auto-dismisses after ~5 s, paused while hovered or focused. */
@Injectable()
export class ToastService {
  readonly current = signal<Toast | null>(null);
  private seq = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;

  show(kind: ToastKind, text: string): void {
    this.current.set({ id: ++this.seq, kind, text });
    this.resume();
  }

  pause(): void {
    this.clearTimer();
  }

  resume(): void {
    this.clearTimer();
    if (this.current()) {
      this.timer = setTimeout(() => this.dismiss(), TOAST_DURATION_MS);
    }
  }

  dismiss(): void {
    this.clearTimer();
    this.current.set(null);
  }

  private clearTimer(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
