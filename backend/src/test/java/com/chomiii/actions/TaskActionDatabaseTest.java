package com.chomiii.actions;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
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
class TaskActionDatabaseTest {
    @Autowired TestRestTemplate http;

    @Test
    void proposalNeedsApprovalAndRepeatedApprovalDoesNotDuplicateTheTask() {
        String a = session();
        String b = session();
        var proposal = request(HttpMethod.POST, "/api/actions/tasks/proposals", a,
                "{\"title\":\"Call the dentist\"}");
        assertThat(proposal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = proposal.getBody().path("id").asText();
        assertThat(proposal.getBody().path("status").asText()).isEqualTo("pending");
        assertThat(request(HttpMethod.GET, "/api/actions/tasks", a, null).getBody()).hasSize(0);
        assertThat(request(HttpMethod.GET, "/api/actions/tasks", b, null).getBody()).hasSize(0);
        assertThat(request(HttpMethod.POST, "/api/actions/tasks/proposals/" + id + "/approve",
                b, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var first = request(HttpMethod.POST, "/api/actions/tasks/proposals/" + id + "/approve", a, null);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String taskId = first.getBody().path("id").asText();
        assertThat(request(HttpMethod.POST, "/api/actions/tasks/proposals/" + id + "/approve",
                a, null).getBody().path("id").asText()).isEqualTo(taskId);
        assertThat(request(HttpMethod.GET, "/api/actions/tasks", a, null).getBody()).hasSize(1);
        assertThat(request(HttpMethod.GET, "/api/actions/tasks", b, null).getBody()).hasSize(0);

        assertThat(request(HttpMethod.POST, "/api/actions/tasks/" + taskId + "/complete",
                b, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(request(HttpMethod.POST, "/api/actions/tasks/" + taskId + "/complete",
                a, null).getBody().path("status").asText()).isEqualTo("completed");
    }

    @Test
    void rejectingAProposalNeverCreatesATask() {
        String a = session();
        String proposal = request(HttpMethod.POST, "/api/actions/tasks/proposals", a,
                "{\"title\":\"Write an email\"}").getBody().path("id").asText();
        assertThat(request(HttpMethod.DELETE, "/api/actions/tasks/proposals/" + proposal,
                a, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(request(HttpMethod.POST, "/api/actions/tasks/proposals/" + proposal + "/approve",
                a, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(request(HttpMethod.GET, "/api/actions/tasks", a, null).getBody()).hasSize(0);
    }

    @Test
    void rejectsCrossOriginAndInvalidProposals() {
        String a = session();
        assertThat(request(HttpMethod.POST, "/api/actions/tasks/proposals", a,
                "{\"title\":\"   \"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, a);
        headers.add(HttpHeaders.ORIGIN, "https://attacker.example");
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.exchange("/api/actions/tasks/proposals", HttpMethod.POST,
                new HttpEntity<>("{\"title\":\"steal task\"}", headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String session() {
        String cookie = http.getForEntity("/api/actions/tasks", String.class)
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
