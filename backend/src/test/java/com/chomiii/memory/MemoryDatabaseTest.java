package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:(postgresql|mysql):.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class MemoryDatabaseTest {
    @Autowired TestRestTemplate http;
    @Autowired MemoryService memories;

    @Test
    void usersCanOnlySeeCorrectOrDeleteTheirOwnExplicitMemories() {
        String a = session();
        String b = session();
        assertThat(a).isNotEqualTo(b);
        var saved = request(HttpMethod.POST, "/api/memories", a,
                "{\"content\":\"My usual groceries are milk and oats\"}");
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = saved.getBody().path("id").asText();
        assertThat(saved.getBody().path("source").asText()).isEqualTo("user_explicit");

        assertThat(request(HttpMethod.GET, "/api/memories", a, null).getBody()).hasSize(1);
        assertThat(request(HttpMethod.GET, "/api/memories", b, null).getBody()).hasSize(0);
        assertThat(request(HttpMethod.PUT, "/api/memories/" + id, b,
                "{\"content\":\"Changed by attacker\"}").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(request(HttpMethod.DELETE, "/api/memories/" + id, b, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(request(HttpMethod.PUT, "/api/memories/" + id, a,
                "{\"content\":\"My usual groceries are fruit\"}").getBody().path("content").asText())
                .isEqualTo("My usual groceries are fruit");
        assertThat(request(HttpMethod.GET, "/api/memories", b, null).getBody()).hasSize(0);
        assertThat(request(HttpMethod.DELETE, "/api/memories/" + id, a, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(request(HttpMethod.GET, "/api/memories", a, null).getBody()).hasSize(0);
    }

    @Test
    void ownerCanClearEverythingWithoutDeletingAnotherUsersMemories() {
        String a = session();
        String b = session();
        request(HttpMethod.POST, "/api/memories", a, "{\"content\":\"First memory\"}");
        request(HttpMethod.POST, "/api/memories", a, "{\"content\":\"Second memory\"}");
        request(HttpMethod.POST, "/api/memories", b, "{\"content\":\"Other memory\"}");

        assertThat(request(HttpMethod.DELETE, "/api/memories", a, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(request(HttpMethod.GET, "/api/memories", a, null).getBody()).hasSize(0);
        assertThat(request(HttpMethod.GET, "/api/memories", b, null).getBody()).hasSize(1);
    }

    @Test
    void cannotSaveBlankOversizedOrCrossOriginMemory() {
        String cookie = session();
        assertThat(request(HttpMethod.POST, "/api/memories", cookie, "{\"content\":\"  \"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(request(HttpMethod.POST, "/api/memories", cookie,
                "{\"content\":\"" + "a".repeat(501) + "\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        headers.add(HttpHeaders.ORIGIN, "https://attacker.example");
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.exchange("/api/memories", HttpMethod.POST,
                new HttpEntity<>("{\"content\":\"stolen\"}", headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(request(HttpMethod.GET, "/api/memories", cookie, null).getBody()).hasSize(0);
    }

    @Test
    void inferenceMustBeReviewedBeforeItCanEnterChatContext() throws Exception {
        String first = session();
        String stranger = session();
        String token = first.substring(first.indexOf('=') + 1);
        String owner = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
        UUID conversation = UUID.randomUUID();
        memories.learn(owner, conversation, 0L, "I might prefer weekend groceries",
                "inferred", 0.75);
        var mine = request(HttpMethod.GET, "/api/memories", first, null).getBody();
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("status").asText()).isEqualTo("suggested");
        assertThat(memories.forChat(owner, "Which groceries do I prefer?")).isEmpty();
        String id = mine.get(0).path("id").asText();
        assertThat(request(HttpMethod.POST, "/api/memories/" + id + "/approve",
                stranger, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(request(HttpMethod.POST, "/api/memories/" + id + "/approve",
                first, null).getBody().path("status").asText()).isEqualTo("active");
        assertThat(memories.forChat(owner, "What are my groceries?"))
                .contains("weekend groceries");
    }

    private String session() {
        String cookie = http.getForEntity("/api/memories", String.class)
                .getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).isNotBlank();
        return cookie.split(";", 2)[0];
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String cookie, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        headers.add(HttpHeaders.ORIGIN, "http://localhost:5173");
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
