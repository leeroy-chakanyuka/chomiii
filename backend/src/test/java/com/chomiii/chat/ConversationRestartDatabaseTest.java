package com.chomiii.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConversationRestartDatabaseTest {
    private static String cookie;
    private static String conversationId;
    private static String requestId;

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void createsConversationBeforeApplicationRestart() {
        String setCookie = http.getForEntity("/api/conversations", String.class)
                .getHeaders().getFirst("Set-Cookie");
        assertThat(setCookie).isNotBlank();
        cookie = setCookie.split(";", 2)[0];
        HttpHeaders headers = headers();
        var response = http.exchange("/api/conversations", HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        conversationId = response.getBody().path("id").asText();
        requestId = UUID.randomUUID().toString();
        jdbc.update("insert into messages (id, conversation_id, sequence_no, request_id, role, content, status, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), conversationId, 1, requestId, "user",
                "Persist this message", "complete", Timestamp.from(Instant.now()));
        jdbc.update("insert into messages (id, conversation_id, sequence_no, request_id, role, content, status, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), conversationId, 2, requestId, "assistant",
                "unfinished reply", "pending", Timestamp.from(Instant.now()));
        jdbc.update("update conversations set active_request_id = ? where id = ?", requestId, conversationId);
    }

    @Test
    @Order(2)
    void reopensConversationAfterApplicationRestart() {
        assertThat(cookie).isNotBlank();
        assertThat(conversationId).isNotBlank();
        var response = http.exchange("/api/conversations/" + conversationId, HttpMethod.GET,
                new HttpEntity<>(headers()), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("id").asText()).isEqualTo(conversationId);
        assertThat(response.getBody().path("messages").get(0).path("content").asText())
                .isEqualTo("Persist this message");
        assertThat(response.getBody().path("messages").get(1).path("status").asText())
                .isEqualTo("interrupted");
        assertThat(jdbc.queryForObject("select active_request_id from conversations where id = ?",
                String.class, conversationId)).isNull();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        headers.add(HttpHeaders.ORIGIN, "http://localhost:5173");
        return headers;
    }
}
