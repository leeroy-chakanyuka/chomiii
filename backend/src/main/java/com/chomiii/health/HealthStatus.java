package com.chomiii.health;

/**
 * Observable health payload of the service. Part of the frontend HTTP contract.
 */
public record HealthStatus(String status, String service) {
}
