import { NgModule } from '@angular/core';
import { RouterModule } from '@angular/router';
import { COMMITS_ROUTES } from './commits.routes';

@NgModule({ imports: [RouterModule.forChild(COMMITS_ROUTES)] })
export class CommitsModule {}
