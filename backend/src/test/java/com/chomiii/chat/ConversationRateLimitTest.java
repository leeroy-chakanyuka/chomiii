package com.chomiii.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "chomiii.public-origin=http://localhost:5174")
@ActiveProfiles("local")
class ConversationRateLimitTest {
    @Autowired
    TestRestTemplate http;

    @Test
    void repeatedRequestsEventuallyReceive429() {
        var first = http.getForEntity("/api/conversations", String.class);
        String cookie = first.getHeaders().getFirst("Set-Cookie").split(";", 2)[0];
        var another = http.getForEntity("/api/conversations", String.class);
        String otherCookie = another.getHeaders().getFirst("Set-Cookie").split(";", 2)[0];
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        HttpHeaders otherHeaders = new HttpHeaders();
        otherHeaders.add(HttpHeaders.COOKIE, otherCookie);

        for (int i = 0; i < 59; i++) {
            assertThat(http.exchange("/api/conversations", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(http.exchange("/api/conversations", HttpMethod.GET,
                new HttpEntity<>(otherHeaders), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.exchange("/api/conversations", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var limited = http.exchange("/api/conversations", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
    }
}
