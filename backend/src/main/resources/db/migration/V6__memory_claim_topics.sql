-- V6: optional structured claim topic for learned memories. The regular
-- extraction pass may label a fact's general subject ("residence",
-- "workplace", "programming language"). The label is provenance metadata
-- used only by the conservative, domain-agnostic conflict gate to recognize
-- corrections that share no wording; user-edited content clears it.
-- Portable across MySQL and PostgreSQL.
ALTER TABLE personal_memories ADD COLUMN claim_topic VARCHAR(120) NULL;
ALTER TABLE personal_memories ADD COLUMN superseded_by CHAR(36) NULL;
