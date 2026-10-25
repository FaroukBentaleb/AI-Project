import { Routes } from '@angular/router';
import { CommitsApiService } from './commits-api.service';
import { CommitsPageComponent } from './commits-page.component';

export const COMMITS_ROUTES: Routes = [
  { path: '', component: CommitsPageComponent, providers: [CommitsApiService] }
];
