package com.chomiii.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class SessionConfiguration {
    @Bean
    FilterRegistrationBean<AnonymousSessionFilter> anonymousSessionFilter(JdbcTemplate jdbc,
            @Value("${chomiii.session.secure:false}") boolean secure,
            @Value("${chomiii.public-origin:http://localhost:5173}") String origin) {
        var registration = new FilterRegistrationBean<>(new AnonymousSessionFilter(jdbc, secure, origin));
        registration.addUrlPatterns("/api/conversations/*", "/api/memories/*");
        return registration;
    }
}
