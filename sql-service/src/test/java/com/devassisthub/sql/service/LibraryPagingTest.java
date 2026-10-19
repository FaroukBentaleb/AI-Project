package com.devassisthub.sql.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devassisthub.sql.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

class LibraryPagingTest {

    @Test
    void defaultsArePage0Size20CreatedAtDesc() {
        Pageable p = LibraryPaging.of(null, null, null);
        assertThat(p.getPageNumber()).isZero();
        assertThat(p.getPageSize()).isEqualTo(20);
        assertThat(p.getSort()).isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    @Test
    void sizeAbove100IsClamped() {
        assertThat(LibraryPaging.of(0, 500, null).getPageSize()).isEqualTo(100);
        assertThat(LibraryPaging.of(0, 100, null).getPageSize()).isEqualTo(100);
    }

    @Test
    void whitelistedFieldsMapToProperties() {
        assertThat(LibraryPaging.of(0, 20, "title").getSort())
                .isEqualTo(Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id")));
        assertThat(LibraryPaging.of(0, 20, "updated_at,desc").getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        assertThat(LibraryPaging.of(0, 20, " created_at , ASC ").getSort())
                .isEqualTo(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"author", "use_count", "last_used_at", "createdAt", "sql_content", "id",
            "title,sideways", "title,asc,extra", ",desc"})
    void unknownSortIs400(String sort) {
        assertBadRequest(() -> LibraryPaging.of(0, 20, sort), "sort");
    }

    @Test
    void negativePageAndNonPositiveSizeAre400() {
        assertBadRequest(() -> LibraryPaging.of(-1, 20, null), "page");
        assertBadRequest(() -> LibraryPaging.of(0, 0, null), "size");
    }

    private static void assertBadRequest(Runnable r, String field) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getError()).isEqualTo("VALIDATION_FAILED");
            assertThat(e.getField()).isEqualTo(field);
        });
    }
}
