-- V1: baseline migration. No application tables exist yet; this proves the
-- Flyway pipeline is applied on every environment and is visible in
-- flyway_schema_history. Application migrations start at V2.
SELECT 1;
