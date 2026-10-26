package com.devassisthub.review.service;

import com.devassisthub.review.dto.CreateReviewRequest;
import com.devassisthub.review.entity.Review;
import com.devassisthub.review.repository.ReviewRepository;
import org.springframework.stereotype.Service;

@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewAiClient reviewAiClient;

    public ReviewService(ReviewRepository reviewRepository, ReviewAiClient reviewAiClient) {
        this.reviewRepository = reviewRepository;
        this.reviewAiClient = reviewAiClient;
    }

    public Review review(CreateReviewRequest request) {
        String aiResponse = reviewAiClient.review(request.codeSnippet(), request.language());
        return reviewRepository.save(new Review(request.codeSnippet(), aiResponse, "COMPLETED"));
    }
}
