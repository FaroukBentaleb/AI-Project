import { NgModule } from '@angular/core';
import { RouterModule } from '@angular/router';
import { SNIPPETS_ROUTES } from './snippets.routes';

@NgModule({ imports: [RouterModule.forChild(SNIPPETS_ROUTES)] })
export class SnippetsModule {}
