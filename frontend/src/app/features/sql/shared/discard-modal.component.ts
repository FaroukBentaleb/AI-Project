import { AfterViewInit, Component, ElementRef, EventEmitter, Output, ViewChild } from '@angular/core';

/** "Discard your changes?" (EXPERIENCE.md). Initial focus on Keep editing; Esc keeps editing; focus is trapped. */
@Component({
  selector: 'sql-discard-modal',
  standalone: true,
  template: `
    <div class="scrim">
      <div #dialog class="modal" role="dialog" aria-modal="true" aria-labelledby="sql-discard-title" (keydown)="onKeydown($event)">
        <h2 id="sql-discard-title" class="modal-title">Discard your changes?</h2>
        <div class="actions">
          <button #keep type="button" class="btn btn-secondary" (click)="keepEditing.emit()">Keep editing</button>
          <button type="button" class="btn btn-primary" (click)="discard.emit()">Discard</button>
        </div>
      </div>
    </div>
  `,
  styleUrl: '../shell/sql-theme.css'
})
export class DiscardModalComponent implements AfterViewInit {
  @Output() readonly keepEditing = new EventEmitter<void>();
  @Output() readonly discard = new EventEmitter<void>();

  @ViewChild('keep', { static: true }) private keep!: ElementRef<HTMLButtonElement>;
  @ViewChild('dialog', { static: true }) private dialog!: ElementRef<HTMLElement>;

  ngAfterViewInit(): void {
    this.keep.nativeElement.focus();
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.keepEditing.emit();
    } else if (event.key === 'Tab') {
      const buttons = Array.from(this.dialog.nativeElement.querySelectorAll<HTMLElement>('button'));
      const first = buttons[0];
      const last = buttons[buttons.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
  }
}
