import { NgModule } from '@angular/core';
import { RouterModule } from '@angular/router';
import { BUGS_ROUTES } from './bugs.routes';

@NgModule({ imports: [RouterModule.forChild(BUGS_ROUTES)] })
export class BugsModule {}
