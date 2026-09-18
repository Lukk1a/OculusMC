DROP TABLE IF EXISTS refresh_tokens;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
    id VARCHAR(255) PRIMARY KEY,
    username VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    totp_secret_enc VARCHAR(255),
    totp_confirmed BOOLEAN NOT NULL DEFAULT 0,
    role VARCHAR(50) NOT NULL DEFAULT 'Admin',
    nodes_json TEXT NOT NULL DEFAULT '[]',
    recovery_hashes TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
