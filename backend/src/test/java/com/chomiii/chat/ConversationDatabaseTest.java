package com.chomiii.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:(postgresql|mysql):.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class ConversationDatabaseTest {
    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void newBrowserSessionStartsWithAnEmptyConversationList() {
        var response = http.getForEntity("/api/conversations", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
        assertThat(response.getHeaders().getFirst("Set-Cookie")).contains("HttpOnly", "SameSite=Lax");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void sessionsCanCreateOpenAndDeleteOnlyTheirOwnConversations() {
        String owner = session();
        String other = session();
        assertThat(owner).isNotEqualTo(other);
        var created = request(HttpMethod.POST, "/api/conversations", owner);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = created.getBody().path("id").asText();
        assertThat(UUID.fromString(id)).isNotNull();

        assertThat(request(HttpMethod.GET, "/api/conversations", owner).getBody()).hasSize(1);
        assertThat(request(HttpMethod.GET, "/api/conversations", other).getBody()).hasSize(0);
        assertThat(request(HttpMethod.GET, "/api/conversations/" + id, other).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(request(HttpMethod.DELETE, "/api/conversations/" + id, other).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(request(HttpMethod.GET, "/api/conversations/" + id, owner).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(request(HttpMethod.DELETE, "/api/conversations/" + id, owner).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(request(HttpMethod.GET, "/api/conversations/" + id, owner).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void historyIsOrderedAndOtherConversationsStaySeparate() {
        String owner = session();
        String first = request(HttpMethod.POST, "/api/conversations", owner).getBody().path("id").asText();
        String second = request(HttpMethod.POST, "/api/conversations", owner).getBody().path("id").asText();
        insertMessage(first, 2, "assistant", "Second");
        insertMessage(first, 1, "user", "First");
        insertMessage(second, 1, "user", "Different chat");

        JsonNode messages = request(HttpMethod.GET, "/api/conversations/" + first, owner)
                .getBody().path("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("content").asText()).isEqualTo("First");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("Second");
    }

    @Test
    void databaseRejectsInvalidMessageRoles() {
        String owner = session();
        String id = request(HttpMethod.POST, "/api/conversations", owner).getBody().path("id").asText();
        assertThatThrownBy(() -> insertMessage(id, 1, "system", "not allowed"))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from messages where conversation_id = ? and role = ?",
                Integer.class, id, "system")).isZero();
    }

    @Test
    void rejectsCrossOriginMutations() {
        String owner = session();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, owner);
        headers.add(HttpHeaders.ORIGIN, "https://attacker.example");
        var response = http.exchange("/api/conversations", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsMutationsWithoutAnOrigin() {
        String owner = session();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, owner);
        var response = http.exchange("/api/conversations", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void rejectsMalformedIdsAndUnknownSessionTokens() {
        String owner = session();
        assertThat(request(HttpMethod.GET, "/api/conversations/not-a-uuid", owner).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "chomiii_session=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        var response = http.exchange("/api/conversations", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getHeaders().getFirst("Set-Cookie"))
                .doesNotContain("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    }

    @Test
    void storesOnlyTheHashOfTheSessionToken() {
        String owner = session();
        String token = owner.substring(owner.indexOf('=') + 1);
        assertThat(token).hasSize(43);
        Integer rawTokens = jdbc.queryForObject("select count(*) from browser_sessions where owner_hash = ?",
                Integer.class, token);
        assertThat(rawTokens).isZero();
    }

    private void insertMessage(String conversation, long sequence, String role, String content) {
        jdbc.update("insert into messages (id, conversation_id, sequence_no, request_id, role, content, status, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), conversation, sequence, UUID.randomUUID().toString(), role,
                content, "complete", Timestamp.from(Instant.now()));
    }

    private String session() {
        String cookie = http.getForEntity("/api/conversations", String.class).getHeaders().getFirst("Set-Cookie");
        assertThat(cookie).isNotBlank();
        return cookie.split(";", 2)[0];
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        headers.add(HttpHeaders.ORIGIN, "http://localhost:5173");
        return http.exchange(path, method, new HttpEntity<>(headers), JsonNode.class);
    }
}
