import { Component, EventEmitter, Input, Output } from '@angular/core';
import { normalizeTags } from '../library/library-state';

/**
 * Tags input (EXPERIENCE.md): Enter or comma commits a chip, Backspace in an empty input removes the last one,
 * each chip has a "Remove tag <name>" button. Text is trimmed and lowercased; duplicates are ignored.
 */
@Component({
  selector: 'sql-tags-input',
  standalone: true,
  template: `
    <div class="tags-input" (click)="input.focus()">
      @for (tag of tags; track tag) {
        <span class="tag">
          {{ tag }}
          <button type="button" class="tag-remove" [attr.aria-label]="'Remove tag ' + tag" (click)="remove(tag); $event.stopPropagation()">×</button>
        </span>
      }
      <input
        #input
        class="tags-field"
        type="text"
        [id]="inputId"
        [attr.aria-label]="ariaLabel"
        [placeholder]="tags.length ? '' : placeholder"
        [attr.maxlength]="maxLength"
        (keydown)="onKeydown($event, input)"
        (blur)="commit(input)"
      />
    </div>
  `,
  styleUrl: './tags-input.component.css'
})
export class TagsInputComponent {
  @Input() tags: string[] = [];
  @Input() inputId = 'sql-tags';
  @Input() ariaLabel = 'Tags';
  @Input() placeholder = 'Add a tag…';
  @Input() maxTags = 10;
  @Input() maxLength = 50;
  @Output() readonly tagsChange = new EventEmitter<string[]>();
  /** Emits a message when a chip is refused because the tag limit is reached (Save form shows it inline). */
  @Output() readonly rejected = new EventEmitter<string>();

  onKeydown(event: KeyboardEvent, input: HTMLInputElement): void {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault();
      this.commit(input);
    } else if (event.key === 'Backspace' && input.value === '' && this.tags.length) {
      event.preventDefault();
      this.emit(this.tags.slice(0, -1));
    }
  }

  commit(input: HTMLInputElement): void {
    const next = normalizeTags([...this.tags, ...input.value.split(',')]);
    if (next.length > this.maxTags) {
      this.rejected.emit(`Up to ${this.maxTags} tags.`);
      return;
    }
    input.value = '';
    if (next.length !== this.tags.length) {
      this.emit(next);
    }
  }

  remove(tag: string): void {
    this.emit(this.tags.filter((t) => t !== tag));
  }

  private emit(next: string[]): void {
    this.tags = next;
    this.tagsChange.emit(next);
  }
}
