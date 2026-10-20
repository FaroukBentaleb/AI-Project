import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Routes } from '@angular/router';
import { GenerateComponent } from './generate/generate.component';
import { InAppHistory } from './core/in-app-history.service';
import { SqlApiService } from './core/sql-api.service';
import { SqlSessionState } from './core/session-state.service';
import { QueryDetailComponent } from './detail/query-detail.component';
import { userIdInterceptor } from './core/user-id.interceptor';
import { LibraryComponent } from './library/library.component';
import { SaveQueryComponent, confirmLeaveSaveForm } from './save/save-query.component';
import { ToastService } from './shared/toast.service';
import { SqlShellComponent } from './shell/sql-shell.component';
import { ValidateComponent } from './validate/validate.component';

export const SQL_ROUTES: Routes = [
  {
    path: '',
    component: SqlShellComponent,
    // Module-scoped HttpClient: X-User-Id is added for the SQL module only; the app-wide client is untouched.
    providers: [provideHttpClient(withInterceptors([userIdInterceptor])), SqlApiService, ToastService, InAppHistory, SqlSessionState],
    children: [
      { path: '', redirectTo: 'library', pathMatch: 'full' },
      { path: 'library', component: LibraryComponent },
      { path: 'queries/new', component: SaveQueryComponent, canDeactivate: [confirmLeaveSaveForm] },
      { path: 'queries/:id', component: QueryDetailComponent },
      { path: 'queries/:id/edit', component: SaveQueryComponent, canDeactivate: [confirmLeaveSaveForm], data: { mode: 'edit' } },
      { path: 'generate', component: GenerateComponent },
      { path: 'validate', component: ValidateComponent },
      { path: '**', redirectTo: 'library' }
    ]
  }
];
