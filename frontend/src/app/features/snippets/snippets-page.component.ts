import { Component, ElementRef, OnDestroy, OnInit, ViewChild, computed, signal } from '@angular/core';
import { Observable, switchMap } from 'rxjs';
import { LANGUAGES, SAMPLES, documentCode, languageLabel, summaryLine, timeAgo } from './snippet-format';
import { SnippetsApiService, errorMessage } from './snippets-api.service';
import { Language, SnippetDetail, SnippetRequest, SnippetSummary } from './snippets.models';

const LAST_LANGUAGE_KEY = 'snippets.lastLanguage';
const MAX_CODE_CHARS = 20000;

type Phase = 'idle' | 'saving' | 'generating';

@Component({
  selector: 'app-snippets-page',
  standalone: true,
  templateUrl: './snippets-page.component.html',
  styleUrl: './snippets-page.component.css'
})
export class SnippetsPageComponent implements OnInit, OnDestroy {
  @ViewChild('result') private resultCard?: ElementRef<HTMLElement>;

  readonly languages = LANGUAGES;
  readonly title = signal('');
  readonly language = signal<Language>(readLastLanguage());
  readonly code = signal('');
  readonly phase = signal<Phase>('idle');
  readonly error = signal('');
  readonly current = signal<SnippetDetail | null>(null);
  readonly library = signal<SnippetSummary[]>([]);
  readonly filterLanguage = signal<Language | ''>('');
  readonly query = signal('');
  readonly editing = signal(false);
  readonly draft = signal('');
  readonly copied = signal<'' | 'documented' | 'docstring'>('');
  readonly confirmDeleteId = signal<number | null>(null);

  readonly busy = computed(() => this.phase() !== 'idle');
  readonly tooLong = computed(() => this.code().length > MAX_CODE_CHARS);
  readonly canSubmit = computed(
    () => !this.busy() && this.title().trim().length > 0 && this.code().trim().length > 0 && !this.tooLong()
  );
  /** The code with its docstring in place, as it would be pasted into a file. */
  readonly documented = computed(() => {
    const snippet = this.current();
    return snippet ? documentCode(snippet.code, snippet.language, snippet.docstring) : '';
  });
  /** True when the form no longer matches the saved snippet. */
  readonly dirty = computed(() => {
    const snippet = this.current();
    return !!snippet && (snippet.title !== this.title().trim() || snippet.language !== this.language()
      || snippet.code !== this.code());
  });

  readonly label = languageLabel;
  readonly summary = summaryLine;
  readonly ago = (iso: string) => timeAgo(iso);

  private copyTimer?: ReturnType<typeof setTimeout>;
  private searchTimer?: ReturnType<typeof setTimeout>;

  constructor(private readonly api: SnippetsApiService) {}

  ngOnInit(): void {
    this.refresh();
  }

  ngOnDestroy(): void {
    clearTimeout(this.copyTimer);
    clearTimeout(this.searchTimer);
  }

  setLanguage(language: Language): void {
    this.language.set(language);
    rememberLanguage(language);
  }

  useSample(): void {
    this.title.set(this.title().trim() || 'sample');
    this.code.set(SAMPLES[this.language()]);
  }

  /** Saves (create or update) and lets the model write the docstring. */
  generate(): void {
    if (!this.canSubmit()) {
      return;
    }
    const existing = this.current();
    this.error.set('');
    this.phase.set('generating');
    const request = this.request(existing?.docstring ?? null);
    const call: Observable<SnippetDetail> = existing
      ? this.api.update(existing.id, request).pipe(switchMap((saved) => this.api.generateDocstring(saved.id)))
      : this.api.create({ ...request, docstring: null, generate: true });
    this.run(call);
  }

  /** Saves (create or update) without asking the model; an existing docstring is kept. */
  save(): void {
    if (!this.canSubmit()) {
      return;
    }
    const existing = this.current();
    this.error.set('');
    this.phase.set('saving');
    const request = this.request(existing?.docstring ?? null);
    this.run(existing ? this.api.update(existing.id, request) : this.api.create(request));
  }

  open(id: number): void {
    this.error.set('');
    this.api.get(id).subscribe({
      next: (detail) => {
        this.show(detail);
        this.fillForm(detail);
        setTimeout(() => this.resultCard?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' }));
      },
      error: (e) => this.error.set(errorMessage(e))
    });
  }

  newSnippet(): void {
    this.current.set(null);
    this.editing.set(false);
    this.title.set('');
    this.code.set('');
    this.error.set('');
  }

  startEdit(): void {
    this.draft.set(this.current()?.docstring ?? '');
    this.editing.set(true);
  }

  cancelEdit(): void {
    this.editing.set(false);
  }

  saveEdit(): void {
    const snippet = this.current();
    if (!snippet) {
      return;
    }
    // Only the docstring changes here: unsaved edits in the form are not part of this save.
    const request: SnippetRequest = {
      title: snippet.title, language: snippet.language, code: snippet.code, docstring: this.draft().trim() || null
    };
    this.api.update(snippet.id, request).subscribe({
      next: (updated) => {
        this.show(updated);
        this.refresh();
      },
      error: (e) => this.error.set(errorMessage(e))
    });
  }

  async copy(kind: 'documented' | 'docstring', text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.copied.set(kind);
      clearTimeout(this.copyTimer);
      this.copyTimer = setTimeout(() => this.copied.set(''), 1600);
    } catch {
      this.error.set('Copy is not available in this browser; select the text and copy it manually.');
    }
  }

  remove(item: SnippetSummary): void {
    this.confirmDeleteId.set(null);
    this.api.delete(item.id).subscribe({
      next: () => {
        if (this.current()?.id === item.id) {
          this.newSnippet();
        }
        this.refresh();
      },
      error: (e) => this.error.set(errorMessage(e))
    });
  }

  setFilterLanguage(language: Language | ''): void {
    this.filterLanguage.set(language);
    this.refresh();
  }

  setQuery(value: string): void {
    this.query.set(value);
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.refresh(), 250);
  }

  private request(docstring: string | null): SnippetRequest {
    return { title: this.title().trim(), language: this.language(), code: this.code(), docstring };
  }

  private run(call: Observable<SnippetDetail>): void {
    call.subscribe({
      next: (snippet) => {
        this.phase.set('idle');
        this.show(snippet);
        this.fillForm(snippet);
        this.refresh();
        setTimeout(() => this.resultCard?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' }));
      },
      error: (e) => {
        this.phase.set('idle');
        this.error.set(errorMessage(e));
      }
    });
  }

  private show(snippet: SnippetDetail): void {
    this.current.set(snippet);
    this.editing.set(false);
    this.draft.set(snippet.docstring ?? '');
  }

  private fillForm(snippet: SnippetDetail): void {
    this.title.set(snippet.title);
    this.language.set(snippet.language);
    this.code.set(snippet.code);
  }

  private refresh(): void {
    this.api.list(this.filterLanguage(), this.query()).subscribe({
      next: (items) => this.library.set(items),
      error: (e) => this.error.set(errorMessage(e))
    });
  }
}

function readLastLanguage(): Language {
  try {
    const value = localStorage.getItem(LAST_LANGUAGE_KEY);
    return LANGUAGES.some((l) => l.id === value) ? (value as Language) : 'python';
  } catch {
    return 'python';
  }
}

function rememberLanguage(language: Language): void {
  try {
    localStorage.setItem(LAST_LANGUAGE_KEY, language);
  } catch {
    // Storage can be unavailable (private mode); remembering the language is only a convenience.
  }
}
