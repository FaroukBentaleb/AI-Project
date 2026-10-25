package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CallerIdentityTest {

    private static String resolve(String header) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (header != null) {
            req.addHeader(CallerIdentity.HEADER, header);
        }
        return new CallerIdentity(req).getUserId();
    }

    @Test
    void headerPresentIsTrimmed() {
        assertThat(resolve("  alice ")).isEqualTo("alice");
    }

    @Test
    void headerAbsent() {
        assertThat(resolve(null)).isEqualTo("anonymous");
    }

    @Test
    void headerEmpty() {
        assertThat(resolve("")).isEqualTo("anonymous");
    }

    @Test
    void headerWhitespace() {
        assertThat(resolve("   ")).isEqualTo("anonymous");
    }
}
