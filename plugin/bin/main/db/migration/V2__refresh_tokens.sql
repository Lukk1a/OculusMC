CREATE TABLE refresh_tokens (
    id VARCHAR(255) PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    token VARCHAR(500) NOT NULL,
    family_id VARCHAR(255) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT 0,
    expires_at TIMESTAMP NOT NULL,
    FOREIGN KEY (username) REFERENCES users(username)
);
