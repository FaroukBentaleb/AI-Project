package com.devassisthub.sql.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@Import({GlobalExceptionHandlerTest.TestController.class, GlobalExceptionHandler.class})
class GlobalExceptionHandlerTest {

    record Body(@NotBlank String title, @NotBlank @Size(max = 5) String sqlContent) {}

    @RestController
    @Validated
    static class TestController {
        @PostMapping("/test/body")
        String body(@Valid @RequestBody Body body) { return "ok"; }

        @GetMapping("/test/boom")
        String boom() { throw new IllegalStateException("secret internal detail"); }

        @GetMapping("/test/items/{id}")
        String item(@PathVariable("id") Long id) { return "ok"; }

        @GetMapping("/test/param")
        String param(@RequestParam("pageSize") int pageSize) { return "ok"; }

        @GetMapping("/test/limited")
        String limited(@RequestParam("pageSize") @Max(100) int pageSize) { return "ok"; }

        @GetMapping("/test/missing")
        String missing() { throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Saved query 7 not found"); }
    }

    @Autowired MockMvc mvc;

    @Test
    void beanValidationFailureUsesSnakeCaseField() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ok\",\"sql_content\":\"toolong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("sql_content"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details[0].field").value("sql_content"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void allFieldErrorsInDetails() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()").value(2));
    }

    @Test
    void malformedJson() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").hasJsonPath())
                .andExpect(jsonPath("$.field").value(nullValue()))
                .andExpect(jsonPath("$.details").hasJsonPath())
                .andExpect(jsonPath("$.details").value(nullValue()));
    }

    @Test
    void typeMismatch() throws Exception {
        mvc.perform(get("/test/items/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("id"));
    }

    @Test
    void missingParam() throws Exception {
        mvc.perform(get("/test/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("page_size"));
    }

    @Test
    void apiException() throws Exception {
        mvc.perform(get("/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Saved query 7 not found"));
    }

    @Test
    void methodNotAllowed() throws Exception {
        mvc.perform(delete("/test/boom"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unhandledExceptionIsGeneric() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().string(not(containsString("secret internal detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void constrainedParamViolation() throws Exception {
        mvc.perform(get("/test/limited").param("pageSize", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("page_size"));
    }

    @Test
    void unmappedPathIsNotFound() throws Exception {
        mvc.perform(get("/no/such/path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void unsupportedMediaTypeKeepsStatus() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value("Unsupported Media Type"))
                .andExpect(jsonPath("$.field").hasJsonPath())
                .andExpect(jsonPath("$.details").hasJsonPath());
    }

    @Test
    void toSnakeCaseMatchesJackson() {
        org.assertj.core.api.Assertions.assertThat(GlobalExceptionHandler.toSnakeCase("userID")).isEqualTo("user_id");
        org.assertj.core.api.Assertions.assertThat(GlobalExceptionHandler.toSnakeCase("sqlContent")).isEqualTo("sql_content");
    }
}
