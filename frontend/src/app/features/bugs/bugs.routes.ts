import { Routes } from '@angular/router';
import { BugsApiService } from './bugs-api.service';
import { BugsPageComponent } from './bugs-page.component';

export const BUGS_ROUTES: Routes = [
  { path: '', component: BugsPageComponent, providers: [BugsApiService] }
];
