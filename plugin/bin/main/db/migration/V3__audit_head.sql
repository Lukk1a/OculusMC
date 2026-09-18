CREATE TABLE audit_head (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    last_hash VARCHAR(255) NOT NULL
);
