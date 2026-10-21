import { AfterViewInit, Component, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { DISPLAY_NAME_MAX, normalizeDisplayName } from '../core/identity.service';

/** "What should we call you?" dialog. Not dismissible on first visit; cancelable when changing the name. */
@Component({
  selector: 'sql-identity-modal',
  standalone: true,
  template: `
    <div class="scrim">
      <div
        #dialog
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sql-identity-title"
        aria-describedby="sql-identity-help"
        (keydown)="onKeydown($event)"
      >
        <h2 id="sql-identity-title" class="modal-title">What should we call you?</h2>
        <p id="sql-identity-help" class="help">Your display name is attached to the queries you save. It is stored in this browser.</p>
        <form (submit)="$event.preventDefault(); submit()">
          <label for="sql-identity-name" class="label">Display name</label>
          <input
            #nameInput
            id="sql-identity-name"
            name="displayName"
            class="text-input"
            type="text"
            autocomplete="nickname"
            [attr.maxlength]="max"
            [value]="name"
            (input)="name = nameInput.value"
            required
          />
          <div class="actions">
            @if (!firstVisit) {
              <button type="button" class="btn btn-secondary" (click)="cancelled.emit()">Cancel</button>
            }
            <button type="submit" class="btn btn-primary" [disabled]="!valid">Continue</button>
          </div>
        </form>
      </div>
    </div>
  `,
  styleUrl: './sql-theme.css'
})
export class IdentityModalComponent implements AfterViewInit {
  /** First visit: no Cancel and Esc is ignored. */
  @Input() firstVisit = true;
  @Input() set initialName(value: string | null) {
    this.name = value ?? '';
  }
  @Output() readonly saved = new EventEmitter<string>();
  @Output() readonly cancelled = new EventEmitter<void>();

  @ViewChild('nameInput', { static: true }) private nameInput!: ElementRef<HTMLInputElement>;
  @ViewChild('dialog', { static: true }) private dialog!: ElementRef<HTMLElement>;

  readonly max = DISPLAY_NAME_MAX;
  name = '';

  get valid(): boolean {
    return normalizeDisplayName(this.name) !== null;
  }

  ngAfterViewInit(): void {
    this.nameInput.nativeElement.focus();
    this.nameInput.nativeElement.select();
  }

  submit(): void {
    const name = normalizeDisplayName(this.name);
    if (name !== null) {
      this.saved.emit(name);
    }
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      if (!this.firstVisit) {
        this.cancelled.emit();
      }
      return;
    }
    if (event.key === 'Tab') {
      this.trapFocus(event);
    }
  }

  private trapFocus(event: KeyboardEvent): void {
    const focusables = Array.from(
      this.dialog.nativeElement.querySelectorAll<HTMLElement>('input, button:not([disabled])')
    );
    if (focusables.length === 0) {
      return;
    }
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && active === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }
}
