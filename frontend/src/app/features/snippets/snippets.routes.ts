import { Routes } from '@angular/router';
import { SnippetsApiService } from './snippets-api.service';
import { SnippetsPageComponent } from './snippets-page.component';

export const SNIPPETS_ROUTES: Routes = [
  { path: '', component: SnippetsPageComponent, providers: [SnippetsApiService] }
];
