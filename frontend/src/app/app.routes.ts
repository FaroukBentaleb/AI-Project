import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: 'commits', loadChildren: () => import('./features/commits/commits.module').then((m) => m.CommitsModule) },
  { path: 'snippets', loadChildren: () => import('./features/snippets/snippets.module').then((m) => m.SnippetsModule) },
  { path: 'bugs', loadChildren: () => import('./features/bugs/bugs.module').then((m) => m.BugsModule) },
  { path: 'sql', loadChildren: () => import('./features/sql/sql.module').then((m) => m.SqlModule) },
  { path: 'reviews', loadChildren: () => import('./features/reviews/reviews.module').then((m) => m.ReviewsModule) },
  { path: '', redirectTo: 'commits', pathMatch: 'full' }
];
