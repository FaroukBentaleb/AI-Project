import { NgModule } from '@angular/core';
import { RouterModule } from '@angular/router';
import { REVIEWS_ROUTES } from './reviews.routes';

@NgModule({ imports: [RouterModule.forChild(REVIEWS_ROUTES)] })
export class ReviewsModule {}
