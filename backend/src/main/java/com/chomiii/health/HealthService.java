package com.chomiii.health;

import org.springframework.stereotype.Service;

/**
 * Answers whether the backend service is currently able to serve traffic.
 * Kept outside the controller so it can be reasoned about and tested
 * independently of the HTTP layer.
 */
@Service
public class HealthService {

    private static final HealthStatus UP = new HealthStatus("UP", "chomiii");

    public HealthStatus current() {
        return UP;
    }
}
