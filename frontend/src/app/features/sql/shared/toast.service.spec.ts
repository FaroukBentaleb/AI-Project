import { fakeAsync, tick } from '@angular/core/testing';
import { TOAST_DURATION_MS, ToastService } from './toast.service';

describe('ToastService', () => {
  it('shows one toast at a time and auto-dismisses after ~5 s', fakeAsync(() => {
    const s = new ToastService();
    s.show('success', 'A');
    s.show('warning', 'B');
    expect(s.current()?.text).toBe('B');
    tick(TOAST_DURATION_MS - 1);
    expect(s.current()).not.toBeNull();
    tick(1);
    expect(s.current()).toBeNull();
  }));

  it('pauses while hovered or focused', fakeAsync(() => {
    const s = new ToastService();
    s.show('success', 'A');
    s.pause();
    tick(TOAST_DURATION_MS * 2);
    expect(s.current()).not.toBeNull();
    s.resume();
    tick(TOAST_DURATION_MS);
    expect(s.current()).toBeNull();
  }));
});
