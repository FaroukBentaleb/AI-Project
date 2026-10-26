package com.devassisthub.review.service;

import com.devassisthub.review.dto.CreateReviewRequest;
import com.devassisthub.review.entity.Review;
import com.devassisthub.review.exception.ReviewServiceException;
import com.devassisthub.review.repository.ReviewRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewAiClient reviewAiClient;

    public ReviewService(ReviewRepository reviewRepository, ReviewAiClient reviewAiClient) {
        this.reviewRepository = reviewRepository;
        this.reviewAiClient = reviewAiClient;
    }

    public List<Review> list() {
        return reviewRepository.findAllByOrderByCreatedAtDescIdDesc();
    }

    @NonNull
    public Review get(long id) {
        return Objects.requireNonNull(reviewRepository.findById(id)
                .orElseThrow(() -> new ReviewServiceException(HttpStatus.NOT_FOUND, "Review not found")));
    }

    public Review create(CreateReviewRequest request) {
        ReviewAiClient.ReviewResult result = reviewAiClient.review(request.codeSnippet(), request.language());
        return reviewRepository.save(new Review(
                request.codeSnippet(), request.language(), result.response(), result.relevantSnippet(),
                result.relevanceScore(), "COMPLETED"));
    }

    public Review update(long id, CreateReviewRequest request) {
        Review review = get(id);
        ReviewAiClient.ReviewResult result = reviewAiClient.review(request.codeSnippet(), request.language());
        review.update(request.codeSnippet(), request.language(), result.response(), result.relevantSnippet(),
                result.relevanceScore());
        return reviewRepository.save(review);
    }

    public void delete(long id) {
        reviewRepository.delete(get(id));
    }
}
