package com.devassisthub.sql.service;

import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

/** Builds a safe {@link Pageable} from raw list parameters: defaults, size clamp, sort whitelist. */
public final class LibraryPaging {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /** snake_case API name -> entity property. use_count / last_used_at join in Story 1.8. */
    static final Map<String, String> SORT_FIELDS = Map.of(
            "title", "title",
            "created_at", "createdAt",
            "updated_at", "updatedAt");

    private LibraryPaging() {
    }

    public static Pageable of(Integer page, Integer size, String sort) {
        int p = page == null ? 0 : page;
        if (p < 0) {
            throw invalid("page", "page: must be greater than or equal to 0");
        }
        int s = size == null ? DEFAULT_SIZE : size;
        if (s < 1) {
            throw invalid("size", "size: must be greater than or equal to 1");
        }
        return PageRequest.of(p, Math.min(s, MAX_SIZE), parseSort(sort));
    }

    static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        }
        String[] parts = sort.split(",", -1);
        if (parts.length > 2) {
            throw invalid("sort", "sort: expected '<field>[,asc|desc]'");
        }
        String property = SORT_FIELDS.get(parts[0].trim());
        if (property == null) {
            throw invalid("sort", "sort: must be one of " + String.join(", ", SORT_FIELDS.keySet().stream().sorted().toList()));
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            String d = parts[1].trim().toLowerCase(Locale.ROOT);
            if (d.equals("desc")) {
                direction = Sort.Direction.DESC;
            } else if (!d.equals("asc")) {
                throw invalid("sort", "sort: direction must be asc or desc");
            }
        }
        // id tie-breaker keeps page boundaries stable when sort values collide.
        return Sort.by(new Sort.Order(direction, property), new Sort.Order(direction, "id"));
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.VALIDATION_FAILED, message, field, null);
    }
}
