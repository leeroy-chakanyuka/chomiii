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
        properties = "chomiii.public-origin=https://chomiii.example")
@ActiveProfiles("prod")
class ConversationHostedSecurityTest {
    @Autowired
    TestRestTemplate http;

    @Test
    void requiresHttpsAndIssuesAHostOnlySecureCookieBehindTheTrustedProxy() {
        assertThat(http.getForEntity("/api/conversations", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-Proto", "https");
        var response = http.exchange("/api/conversations", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Set-Cookie"))
                .startsWith("__Host-chomiii_session=")
                .contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/")
                .doesNotContain("Domain=");
    }
}
