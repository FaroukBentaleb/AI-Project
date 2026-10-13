import { NgModule } from '@angular/core';
import { RouterModule } from '@angular/router';
import { SQL_ROUTES } from './sql.routes';

@NgModule({ imports: [RouterModule.forChild(SQL_ROUTES)] })
export class SqlModule {}
