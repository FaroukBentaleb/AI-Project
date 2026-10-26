import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, signal } from '@angular/core';
import { Review, ReviewRequest, ReviewSection } from './reviews.models';
import { ReviewsApiService, reviewErrorMessage } from './reviews-api.service';

interface ReviewExample {
  label: string;
  language: string;
  code: string;
}

const EXAMPLES: ReviewExample[] = [
  {
    label: 'Java: unsafe input',
    language: 'Java',
    code: `public String findUser(String userId) throws Exception {
    String query = "SELECT * FROM users WHERE id = '" + userId + "'";
    Statement statement = connection.createStatement();
    ResultSet result = statement.executeQuery(query);
    result.next();
    return result.getString("name");
}`
  },
  {
    label: 'Python: exposed secret',
    language: 'Python',
    code: `API_KEY = "DEMO_SECRET_DO_NOT_USE"

def fetch_user(user_id):
    response = requests.get("https://api.example.com/users/" + user_id)
    print("Request headers:", response.request.headers)
    return response.json()["user"]`
  },
  {
    label: 'TypeScript: async error handling',
    language: 'TypeScript',
    code: `async function loadProfile(userId: string) {
  const response = await fetch('/api/users/' + userId);
  const profile = await response.json();
  return profile.name.toUpperCase();
}`
  }
];

@Component({
  selector: 'app-reviews-page',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './reviews-page.component.html',
  styleUrl: './reviews-page.component.css'
})
export class ReviewsPageComponent implements OnInit {
  readonly examples = EXAMPLES;
  readonly languages = ['Java', 'Python', 'TypeScript', 'JavaScript', 'C#', 'Go', 'SQL', 'Other'];
  readonly code = signal('');
  readonly language = signal('Java');
  readonly busy = signal(false);
  readonly loadingHistory = signal(false);
  readonly error = signal('');
  readonly history = signal<Review[]>([]);
  readonly selected = signal<Review | null>(null);
  readonly editingId = signal<number | null>(null);
  readonly search = signal('');
  readonly languageFilter = signal('');
  readonly confirmDeleteId = signal<number | null>(null);
  readonly copied = signal<'' | 'code' | 'review'>('');
  readonly lineCount = computed(() => this.code().split('\n').length);
  readonly lineNumbers = computed(() => Array.from(
    { length: Math.max(this.lineCount(), 12) },
    (_value, index) => index + 1
  ));
  readonly visibleHistory = computed(() => {
    const query = this.search().trim().toLowerCase();
    return this.history().filter((review) => {
      const matchesQuery = !query
        || review.code_snippet.toLowerCase().includes(query)
        || review.ai_response.toLowerCase().includes(query);
      return matchesQuery && (!this.languageFilter() || review.language === this.languageFilter());
    });
  });
  readonly sections = computed(() => parseSections(this.selected()?.ai_response ?? ''));
  readonly findingCount = computed(() => {
    const response = this.selected()?.ai_response ?? '';
    return (response.match(/\[(?:CRITICAL|HIGH|MEDIUM|LOW)\]/gi) ?? []).length;
  });
  readonly highRiskCount = computed(() => {
    const response = this.selected()?.ai_response ?? '';
    return (response.match(/\[(?:CRITICAL|HIGH)\]/gi) ?? []).length;
  });

  private copyTimer?: ReturnType<typeof setTimeout>;

  constructor(private readonly api: ReviewsApiService) {}

  ngOnInit(): void {
    this.refresh();
  }

  setCode(value: string): void {
    this.code.set(value);
  }

  setLanguage(value: string): void {
    this.language.set(value);
  }

  loadExample(example: ReviewExample): void {
    this.error.set('');
    this.code.set(example.code);
    this.language.set(example.language);
    this.editingId.set(null);
    this.selected.set(null);
  }

  chooseExample(value: string): void {
    const index = Number(value);
    if (Number.isInteger(index) && this.examples[index]) {
      this.loadExample(this.examples[index]);
    }
  }

  startNew(): void {
    this.code.set('');
    this.language.set('Java');
    this.editingId.set(null);
    this.selected.set(null);
    this.error.set('');
  }

  editSelected(): void {
    const review = this.selected();
    if (!review || this.busy()) {
      return;
    }
    this.code.set(review.code_snippet);
    this.language.set(review.language);
    this.editingId.set(review.id);
    this.error.set('');
  }

  cancelEdit(): void {
    this.editingId.set(null);
    this.code.set('');
    this.language.set('Java');
  }

  submit(): void {
    const source = this.code().trim();
    if (this.busy() || !source) {
      return;
    }

    const request: ReviewRequest = { code_snippet: source, language: this.language() };
    const id = this.editingId();
    this.busy.set(true);
    this.error.set('');
    const call = id === null ? this.api.create(request) : this.api.update(id, request);
    call.subscribe({
      next: (review) => {
        this.busy.set(false);
        this.editingId.set(null);
        this.selected.set(review);
        this.code.set('');
        this.language.set('Java');
        this.refresh();
      },
      error: (error) => {
        this.busy.set(false);
        this.error.set(reviewErrorMessage(error));
      }
    });
  }

  open(review: Review): void {
    this.error.set('');
    this.api.get(review.id).subscribe({
      next: (detail) => {
        this.selected.set(detail);
        this.editingId.set(null);
      },
      error: (error) => this.error.set(reviewErrorMessage(error))
    });
  }

  remove(review: Review): void {
    this.confirmDeleteId.set(null);
    this.api.delete(review.id).subscribe({
      next: () => {
        if (this.selected()?.id === review.id) {
          this.selected.set(null);
        }
        if (this.editingId() === review.id) {
          this.editingId.set(null);
          this.code.set('');
        }
        this.refresh();
      },
      error: (error) => this.error.set(reviewErrorMessage(error))
    });
  }

  async copy(kind: 'code' | 'review', text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.copied.set(kind);
      clearTimeout(this.copyTimer);
      this.copyTimer = setTimeout(() => this.copied.set(''), 1600);
    } catch {
      this.error.set('Copy is unavailable in this browser. Select the text and copy it manually.');
    }
  }

  trackReview(_index: number, review: Review): number {
    return review.id;
  }

  private refresh(): void {
    this.loadingHistory.set(true);
    this.api.list().subscribe({
      next: (reviews) => {
        this.history.set(reviews);
        this.loadingHistory.set(false);
      },
      error: (error) => {
        this.loadingHistory.set(false);
        this.error.set(reviewErrorMessage(error));
      }
    });
  }
}

function parseSections(response: string): ReviewSection[] {
  const sections: ReviewSection[] = [];
  let current: ReviewSection | null = null;
  for (const line of response.split(/\r?\n/)) {
    const heading = line.match(/^##\s+(.+)$/);
    if (heading) {
      if (current) {
        current.content = current.content.trim();
        sections.push(current);
      }
      current = { title: heading[1].trim(), content: '' };
    } else if (current) {
      current.content += `${line}\n`;
    }
  }
  if (current) {
    current.content = current.content.trim();
    sections.push(current);
  }
  return sections.length > 0 ? sections : [{ title: 'Review', content: response.trim() }];
}
