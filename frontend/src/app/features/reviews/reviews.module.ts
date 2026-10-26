import { NgModule } from '@angular/core';
import { RouterModule } from '@angular/router';
import { REVIEWS_ROUTES } from './reviews.routes';
import { ReviewsApiService } from './reviews-api.service';

@NgModule({ imports: [RouterModule.forChild(REVIEWS_ROUTES)], providers: [ReviewsApiService] })
export class ReviewsModule {}
