package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import com.devassisthub.sql.support.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** PATCH/PUT against real PostgreSQL. Uses java.net.http because the default TestRestTemplate cannot send PATCH. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class SavedQueryEditIT extends AbstractIntegrationTest {


    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    private record Resp(int status, JsonNode body) { }

    private Resp send(String method, String path, String user, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (user != null) {
            b.header("X-User-Id", user);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body() == null || r.body().isEmpty() ? null : mapper.readTree(r.body()));
    }

    private JsonNode create() throws Exception {
        Resp r = send("POST", "/api/sql/queries", "alice", """
                {"title":"Orders","description":"Weekly orders","sql_content":"SELECT 1","tags":["r"],
                 "notes":"n","is_llm_generated":true,"original_nl_prompt":"orders"}""");
        assertThat(r.status()).isEqualTo(201);
        return r.body();
    }

    private static OffsetDateTime ts(JsonNode n, String k) {
        return OffsetDateTime.parse(n.get(k).asText());
    }

    private void assertUnchanged(JsonNode before, long id) throws Exception {
        JsonNode now = send("GET", "/api/sql/queries/" + id, null, null).body();
        for (String k : new String[] {"title", "description", "sql_content", "tags", "notes", "dialect",
                "author", "is_llm_generated", "original_nl_prompt", "version"}) {
            assertThat(now.get(k)).as(k).isEqualTo(before.get(k));
        }
        assertThat(ts(now, "updated_at").toInstant()).isEqualTo(ts(before, "updated_at").toInstant());
    }

    @Test
    void patchOnlyTagsLeavesOtherFieldsAndAdvancesUpdatedAt() throws Exception {
        JsonNode c = create();
        long id = c.get("id").asLong();
        Thread.sleep(5);
        Resp r = send("PATCH", "/api/sql/queries/" + id, "bob", "{\"tags\":[\"ops\",\"x\"],\"version\":0}");
        assertThat(r.status()).isEqualTo(200);
        JsonNode p = r.body();
        assertThat(p.get("tags").toString()).isEqualTo("[\"ops\",\"x\"]");
        for (String k : new String[] {"title", "description", "sql_content", "notes", "dialect", "author",
                "is_llm_generated", "original_nl_prompt"}) {
            assertThat(p.get(k)).as(k).isEqualTo(c.get(k));
        }
        assertThat(p.get("version").asInt()).isEqualTo(1);
        assertThat(p.get("warnings").isArray()).isTrue();
        assertThat(ts(p, "updated_at")).isAfter(ts(c, "updated_at"));
        assertThat(ts(p, "created_at").toInstant()).isEqualTo(ts(c, "created_at").toInstant());
        JsonNode g = send("GET", "/api/sql/queries/" + id, null, null).body();
        assertThat(g.get("tags")).isEqualTo(p.get("tags"));
        assertThat(g.get("description")).isEqualTo(c.get("description"));
        assertThat(g.get("version").asInt()).isEqualTo(1);
    }

    @Test
    void putReplacesAllFieldsButKeepsAuthorAndCreatedAt() throws Exception {
        JsonNode c = create();
        long id = c.get("id").asLong();
        Thread.sleep(5);
        Resp r = send("PUT", "/api/sql/queries/" + id, "bob", """
                {"title":"New","description":"New desc","sql_content":"SELECT 2","version":0,
                 "author":"mallory","created_at":"2000-01-01T00:00:00Z","id":999}""");
        assertThat(r.status()).isEqualTo(200);
        JsonNode p = r.body();
        assertThat(p.get("id").asLong()).isEqualTo(id);
        assertThat(p.get("title").asText()).isEqualTo("New");
        assertThat(p.get("tags").size()).isZero();
        assertThat(p.get("notes").isNull()).isTrue();
        assertThat(p.get("original_nl_prompt").isNull()).isTrue();
        assertThat(p.get("is_llm_generated").asBoolean()).isFalse();
        assertThat(p.get("dialect").asText()).isEqualTo("postgresql");
        assertThat(p.get("author").asText()).isEqualTo("alice");
        assertThat(ts(p, "created_at").toInstant()).isEqualTo(ts(c, "created_at").toInstant());
        assertThat(ts(p, "updated_at")).isAfter(ts(c, "updated_at"));
        assertThat(p.get("version").asInt()).isEqualTo(1);
        JsonNode g = send("GET", "/api/sql/queries/" + id, null, null).body();
        assertThat(g.get("author").asText()).isEqualTo("alice");
        assertThat(g.get("title").asText()).isEqualTo("New");
        assertThat(ts(g, "created_at").toInstant()).isEqualTo(ts(c, "created_at").toInstant());
    }

    @Test
    void putMissingDescriptionIs400AndNothingChanges() throws Exception {
        JsonNode c = create();
        long id = c.get("id").asLong();
        Resp r = send("PUT", "/api/sql/queries/" + id, "bob",
                "{\"title\":\"New\",\"sql_content\":\"SELECT 2\",\"version\":0}");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("error").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(r.body().get("field").asText()).isEqualTo("description");
        assertUnchanged(c, id);
    }

    @Test
    void staleVersionIs409AndNothingChanges() throws Exception {
        JsonNode c = create();
        long id = c.get("id").asLong();
        JsonNode v1 = send("PATCH", "/api/sql/queries/" + id, "bob", "{\"notes\":\"edit\",\"version\":0}").body();
        assertThat(v1.get("version").asInt()).isEqualTo(1);

        Resp p = send("PATCH", "/api/sql/queries/" + id, "carol", "{\"title\":\"Stale\",\"version\":0}");
        assertThat(p.status()).isEqualTo(409);
        assertThat(p.body().get("error").asText()).isEqualTo("CONFLICT");
        Resp u = send("PUT", "/api/sql/queries/" + id, "carol",
                "{\"title\":\"Stale\",\"description\":\"d\",\"sql_content\":\"SELECT 3\",\"version\":0}");
        assertThat(u.status()).isEqualTo(409);
        assertThat(u.body().get("error").asText()).isEqualTo("CONFLICT");
        Resp ahead = send("PATCH", "/api/sql/queries/" + id, "carol", "{\"title\":\"Ahead\",\"version\":5}");
        assertThat(ahead.status()).isEqualTo(409);
        assertUnchanged(v1, id);
    }

    @Test
    void unknownIdIs404ForPatchAndPut() throws Exception {
        Resp p = send("PATCH", "/api/sql/queries/987654", "bob", "{\"title\":\"x\",\"version\":0}");
        assertThat(p.status()).isEqualTo(404);
        assertThat(p.body().get("error").asText()).isEqualTo("NOT_FOUND");
        Resp u = send("PUT", "/api/sql/queries/987654", "bob",
                "{\"title\":\"t\",\"description\":\"d\",\"sql_content\":\"SELECT 1\",\"version\":0}");
        assertThat(u.status()).isEqualTo(404);
        assertThat(u.body().get("error").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void patchBlankRequiredFieldIs400AndNothingChanges() throws Exception {
        JsonNode c = create();
        long id = c.get("id").asLong();
        Resp r = send("PATCH", "/api/sql/queries/" + id, "bob", "{\"description\":\"  \",\"version\":0}");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("field").asText()).isEqualTo("description");
        assertUnchanged(c, id);
    }
}
