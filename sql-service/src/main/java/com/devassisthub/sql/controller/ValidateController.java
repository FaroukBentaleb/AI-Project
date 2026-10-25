package com.devassisthub.sql.controller;

import com.devassisthub.sql.dto.ValidateRequest;
import com.devassisthub.sql.dto.ValidateResponse;
import com.devassisthub.sql.validation.ReadOnlyValidator;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** FR-12: standalone validation. Parses only; never executes SQL, never saves. Accepted or rejected is always 200. */
@RestController
public class ValidateController {

    private final ReadOnlyValidator validator;

    public ValidateController(ReadOnlyValidator validator) {
        this.validator = validator;
    }

    @PostMapping("/api/sql/validate")
    public ValidateResponse validate(@Valid @RequestBody ValidateRequest request) {
        return ValidateResponse.from(validator.validate(request.sqlContent()));
    }
}
