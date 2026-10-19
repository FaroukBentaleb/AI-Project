import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

/** Temporary screen for routes whose story has not shipped yet (replaced by Stories 3.2–3.5, 4.x, 7.x). */
@Component({
  selector: 'sql-placeholder',
  standalone: true,
  template: `
    <section class="placeholder">
      <h1 class="page-title">{{ title }}</h1>
      <p class="muted">This screen is coming soon.</p>
    </section>
  `,
  styleUrl: './sql-theme.css'
})
export class PlaceholderComponent {
  readonly title: string = inject(ActivatedRoute).snapshot.data['title'] ?? 'SQL';
}
