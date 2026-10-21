import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <nav class="app-nav" aria-label="Modules">
      <span class="brand">DevAssist Hub</span>
      <a routerLink="/commits" routerLinkActive="active">Commits</a>
      <a routerLink="/snippets" routerLinkActive="active">Snippets</a>
      <a routerLink="/bugs" routerLinkActive="active">Bugs</a>
      <a routerLink="/sql" routerLinkActive="active">SQL</a>
      <a routerLink="/reviews" routerLinkActive="active">Reviews</a>
    </nav>
    <router-outlet />
  `,
  styles: [`
    .app-nav { display: flex; gap: 16px; align-items: center; padding: 8px 16px;
      background: #0d1117; border-bottom: 1px solid #30363d; font: 14px system-ui, sans-serif; }
    .brand { font-weight: 600; color: #e6edf3; margin-right: 8px; }
    a { color: #8b949e; text-decoration: none; padding: 4px 0; }
    a:hover { color: #e6edf3; }
    a.active { color: #e6edf3; border-bottom: 2px solid #58a6ff; }
  `]
})
export class AppComponent {}
