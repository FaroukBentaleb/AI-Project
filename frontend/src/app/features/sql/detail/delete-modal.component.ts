import { AfterViewInit, Component, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { trapTab } from '../shared/focus-trap';

/** "Delete '<title>'?" (EXPERIENCE.md). Focus starts on Cancel; Esc cancels; the host owns the API call. */
@Component({
  selector: 'sql-delete-modal',
  standalone: true,
  template: `
    <div class="scrim">
      <div #dialog class="modal" role="dialog" aria-modal="true" aria-labelledby="sql-delete-title" aria-describedby="sql-delete-body"
        (keydown)="onKeydown($event)">
        <h2 id="sql-delete-title" class="modal-title">Delete '{{ title }}'?</h2>
        <p id="sql-delete-body" class="muted">It will be removed from the library and team examples.</p>
        @if (error) {
          <p class="modal-error" role="alert">{{ error }}</p>
        }
        <div class="actions">
          <button #cancelBtn type="button" class="btn btn-secondary" (click)="cancelled.emit()">Cancel</button>
          <button type="button" class="btn btn-danger" [disabled]="busy" (click)="confirmed.emit()">{{ busy ? 'Deleting…' : 'Delete' }}</button>
        </div>
      </div>
    </div>
  `,
  styleUrl: '../shell/sql-theme.css'
})
export class DeleteModalComponent implements AfterViewInit {
  @Input({ required: true }) title = '';
  @Input() busy = false;
  @Input() error: string | null = null;
  @Output() readonly confirmed = new EventEmitter<void>();
  @Output() readonly cancelled = new EventEmitter<void>();

  @ViewChild('cancelBtn', { static: true }) private cancelBtn!: ElementRef<HTMLButtonElement>;
  @ViewChild('dialog', { static: true }) private dialog!: ElementRef<HTMLElement>;

  ngAfterViewInit(): void {
    this.cancelBtn.nativeElement.focus();
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      if (!this.busy) {
        this.cancelled.emit();
      }
    } else {
      trapTab(event, this.dialog.nativeElement);
    }
  }
}
