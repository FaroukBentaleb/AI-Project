package com.devassisthub.bug.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiRedactorTest {

    private final PiiRedactor redactor = new PiiRedactor();

    @Test
    void masksEmailsTokensAndSecrets() {
        PiiRedactor.Result r = redactor.redact("Mail john.doe@example.com, header Authorization: Bearer abcdef1234567890 "
                + "and password=hunter2 with key hf_abcdefghijklmnopqrstuv");

        assertThat(r.text()).doesNotContain("john.doe", "abcdef1234567890", "hunter2", "hf_abcdefghijklmnopqrstuv")
                .contains("[EMAIL]", "Bearer [TOKEN]", "password=[SECRET]", "[TOKEN]");
        assertThat(r.count()).isEqualTo(4);
    }

    @Test
    void masksPublicIpsButKeepsLoopback() {
        PiiRedactor.Result r = redactor.redact("Connection from 83.12.45.7 refused; server on 127.0.0.1:8080");

        assertThat(r.text()).isEqualTo("Connection from [IP] refused; server on 127.0.0.1:8080");
        assertThat(r.count()).isEqualTo(1);
    }

    @Test
    void masksUserNamesInHomePathsAndUrlCredentials() {
        PiiRedactor.Result r = redactor.redact("at C:\\Users\\Safwen\\app\\Main.java and /home/alice/app, "
                + "db postgres://admin:s3cret@db.local/app");

        assertThat(r.text()).contains("C:\\Users\\[USER]\\app", "/home/[USER]/app", "postgres://[SECRET]@db.local")
                .doesNotContain("Safwen", "alice", "s3cret");
    }

    @Test
    void masksInternationalPhoneNumbers() {
        assertThat(redactor.redact("call me at +216 22 333 444 please").text()).isEqualTo("call me at [PHONE] please");
    }

    @Test
    void leavesStackTracesAndVersionsAlone() {
        String trace = "java.lang.NullPointerException\n\tat com.acme.Cart.total(Cart.java:42)\nversion 3.2.1";

        PiiRedactor.Result r = redactor.redact(trace);

        assertThat(r.text()).isEqualTo(trace);
        assertThat(r.count()).isZero();
    }

    @Test
    void nullAndEmptyAreUnchanged() {
        assertThat(redactor.redact(null).text()).isNull();
        assertThat(redactor.redact("").count()).isZero();
    }
}
