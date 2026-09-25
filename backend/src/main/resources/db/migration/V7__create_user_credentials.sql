-- =============================================================================
-- V7__create_user_credentials.sql
-- User Credentials Schema for Password Authentication
-- Secure, isolated storage for password hashes with foreign key to users table.
-- =============================================================================

CREATE TABLE user_credentials (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_user_credentials_user_id UNIQUE (user_id),
    CONSTRAINT fk_user_credentials_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_credentials_user_id ON user_credentials(user_id);
