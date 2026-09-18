# Agent Rules & Security Invariants for Oculus

To prevent security theater, authorization bypasses, and dangerous shortcuts, any agent working on this codebase MUST strictly adhere to the following rules:

## 1. Zero Security Theater
- **Real Authorization**: Never write mock, stub, or pass-through authorization checks (`require()` MUST inspect principal roles and permission nodes, rejecting unauthorized callers with HTTP 403 `forbidden`).
- **No Insecure Fallbacks**: Never provide hardcoded default JWT signing secrets, dummy passwords, or bypass keys. Signing keys MUST be cryptographically generated (256-bit HMAC minimum) and persisted in `jwt.key`.
- **No Environment Auth Bypasses**: Never add bypasses such as `if (process.env.NODE_ENV === "development") return next()` to frontend or backend auth gates.

## 2. Strict Session & Token Lifecycle
- **Cookie Security**: Refresh tokens and PreAuth tokens MUST only be transmitted via `HttpOnly`, `SameSite=Strict`, `Secure` cookies scoped to `/api/auth`. Never leak refresh tokens into JSON response bodies or client-side storage (`localStorage` / `sessionStorage`).
- **Token Family Rotation**: Refresh tokens must follow rotation with family reuse detection. When an already-used or revoked refresh token is presented, the ENTIRE token family MUST be immediately invalidated in SQLite.
- **Server-Side 2FA State**: TOTP secrets generated during initial setup or 2FA enrollment must NEVER be returned to the client to send back during verification. Pending TOTP secrets MUST be held server-side, tied to the short-lived PreAuth session.
- **Rate Limiting & Lockout**: Auth endpoints MUST enforce strict IP rate limiting (30 req/min) and progressive account lockout after failed credential attempts.

## 3. Strict Concurrency & Database Operations
- **Single Connection / Transaction Scope**: When operating on SQLite, never nest connections or initiate secondary write queries from within an open `ResultSet` cursor, as this causes `SQLITE_BUSY` database lockouts. Reuse the connection or complete the read cursor first.
- **SQLite Concurrency Settings**: Always configure SQLite connections with `WAL` journal mode and a positive `busy_timeout` (minimum 5000ms).

## 4. Minecraft Thread Safety (*Hop*)
- **Bukkit Main Thread Safety**: Never touch Bukkit/Paper world or player state directly from Jetty/Javalin threads. All game state access MUST *hop* to the main server thread using `ThreadExecutor.supply()`.
- **Honest Non-Mocking**: If `Bukkit.getServer() == null` (such as in headless unit tests), mutating endpoints MUST return 503 `bukkit_unavailable` rather than silently succeeding or faking operations.

## 5. SSRF & Path Jail
- **Path Traversal Protection (*Jail*)**: All file access MUST be canonicalized and validated to stay strictly within the designated jail root (`Bukkit.getWorldContainer()`).
- **No Unrestricted Downloads**: Endpoints that download files (e.g. plugins/packages) MUST enforce a strict domain allowlist (e.g. Hangar / Modrinth only) and sanitized filenames, or remain disabled until allowlisting is complete.
