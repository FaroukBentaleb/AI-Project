package com.devassisthub.review.controller;

import com.devassisthub.review.dto.CreateReviewRequest;
import com.devassisthub.review.entity.Review;
import com.devassisthub.review.service.ReviewService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Review create(@Valid @RequestBody CreateReviewRequest request) {
        return reviewService.create(request);
    }

    @GetMapping
    public List<Review> list() {
        return reviewService.list();
    }

    @GetMapping("/{id}")
    public Review get(@PathVariable long id) {
        return reviewService.get(id);
    }

    @PutMapping("/{id}")
    public Review update(@PathVariable long id, @Valid @RequestBody CreateReviewRequest request) {
        return reviewService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        reviewService.delete(id);
    }
}
