# Project: Oculus Codebase Audit & Continuous Improvement

## Architecture
Oculus is an enterprise-grade Minecraft management system consisting of two primary components:
1. **`plugin/` (Backend)**: Java 21 / Paper Bukkit plugin embedding a Javalin 6 (Jetty) REST & WebSocket API, SQLite storage with WAL mode, PBKDF2 user authentication with TOTP 2FA, file jail security sandboxing, and Apollo integration.
2. **`web/` (Frontend)**: Next.js 16 (App Router / Turbopack) / React 19 / Tailwind CSS v4 single-page dashboard application exporting to static web assets served by Javalin's Jetty server.

### Cross-System Invariants (AGENTS.md)
1. **Zero Security Theater**: Real RBAC in `JavalinServer.require()`, no default JWT keys, no environment bypasses.
2. **Strict Session Lifecycle**: HttpOnly/SameSite=Strict cookies, refresh token family rotation in SQLite, server-side 2FA state, rate limiting & lockout.
3. **Strict Concurrency & SQLite**: No write operations or nested connections within active `ResultSet` cursors, WAL mode, busy_timeout >= 5000ms.
4. **Minecraft Thread Safety (*Hop*)**: All Bukkit state access must hop to main thread via `ThreadExecutor.supply()`. Headless mutation calls return 503 `bukkit_unavailable`.
5. **SSRF & Path Jail**: Canonical real path jail root within `Bukkit.getWorldContainer()`, strict package download allowlist.

---

## Feature Inventory
Every issue and improvement area identified by the initial survey and the user liaison code review report is cataloged below with its assigned milestone:

| # | Issue / Feature | Description | Milestone | Source |
|---|-----------------|-------------|-----------|--------|
| 1 | SQLite Concurrency in AuthService | `refreshToken()` calls `revokeFamily` inside open ResultSet cursor causing SQLITE_BUSY | M1 | Backend/Security Survey & Code Review |
| 2 | SQLite Concurrency in AuditService | `initializeHead()` opens secondary write connection inside open ResultSet cursor | M1 | Backend Survey |
| 3 | Untrusted IP Resolution | `ClientIpResolver` trusts `X-Forwarded-For` without validating against trusted proxies | M1 | Backend Survey |
| 4 | File Jail db-wal & symlink escape | `FileJail` uses lexical normalization and fails to protect `oculus.db-wal` / sensitive files | M1 | Backend/Security Survey |
| 5 | Timing Attack in Password Verification | `verifyPasswordHash` uses non-constant-time `Arrays.equals` | M1 | Backend Survey |
| 6 | Default Platform Encoding in Audit | `AuditService` writes audit logs with system default charset instead of UTF-8 | M1 | Backend Survey |
| 7 | Main Thread Hop in DashboardController | `getStats` and `getDetailedPlayers` touch Bukkit chunks/entities on Javalin threads | M2 | Backend/Security Survey & Code Review |
| 8 | Timeout Error Spec Compliance | `DashboardController` returns 504 `{"error": "timeout"}` instead of `{"error": "main_thread_timeout"}` | M2 | Code Review Report |
| 9 | Honest Non-Mocking (503) | `PlayersController` & `FilesController` fail to return 503 `bukkit_unavailable` when `Bukkit.getServer() == null` | M2 | Backend/Security Survey & Code Review |
| 10 | Invented Error Codes in AuthController | Returns `invalid_payload` / `missing_credentials` instead of spec-compliant auth errors | M2 | Code Review Report |
| 11 | Package Install Disabling | `/api/packages/install` hardcodes 403 instead of remaining unregistered | M2 | Code Review Report |
| 12 | Next.js Resolve Order in JavalinServer | `if (path.contains(".") && !path.endsWith(".html")) return;` breaks dynamic client routes | M2 | Code Review Report |
| 13 | ApolloService Async Commit Bug | Async route omits `context.future(...)`, prematurely committing empty 200 responses | M2 | Backend Survey |
| 14 | Delete Scratch Files | Remove `test_apollo.java`, `test_file.java`, and compiled `.class` files in repository root | M2 | Code Review Report |
| 15 | Player Data Loading Bug | `players/page.tsx` & `ClientPage.tsx` check `if (res.ok)` on parsed JSON array/object | M3 | Frontend Survey |
| 16 | World Actions Serialization Bug | `worlds/page.tsx` sends raw JS object instead of serialized JSON payload | M3 | Frontend Survey |
| 17 | Broken Token Retrieval in Files & Backups | `localStorage.getItem("oculus_token")` returns null, failing uploads & leaking query tokens | M3 | Frontend/Security Survey |
| 18 | Dashboard Layout Hard Reloads | Native `<a href>` tags in `layout.tsx` wipe in-memory access token on navigation | M3 | Frontend Survey |
| 19 | Missing Map Navigation Item | Sidebar nav is missing `/dashboard/map` route entry | M3 | Frontend Survey |
| 20 | WebSocket Reconnect Token Expiry | `ConsoleWebSocket` reconnects infinitely on expired tokens without refresh | M3 | Frontend Survey |
| 21 | Missing CSS Classes & Design Tokens | `globals.css` lacks `.linear-panel`, `.tracking-heading`, and color tokens | M4 | Frontend Survey |
| 22 | Missing React Error Boundaries | Add `dashboard/error.tsx` and `global-error.tsx` to handle uncaught client exceptions | M4 | Frontend Survey |
| 23 | Inventory Slot Accessibility | Replace non-interactive `<div>`s with accessible interactive buttons and ARIA labels | M4 | Frontend Survey |
| 24 | Form Label Associations | Ensure Apollo waypoint and command inputs have proper `<label>` associations | M4 | Frontend Survey |
| 25 | Regression & Verification Test Suite | Expand unit & integration tests for all fixed vulnerabilities and edge cases | M5 | Acceptance Criteria |
| 26 | Final Audit Results Report | Generate `audit_results.md` at root summarizing all findings and verified fixes | M5 | Acceptance Criteria |

---

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Backend Security & Concurrency Invariants | Fix SQLite ResultSet cursor concurrency, IP resolver proxy validation, FileJail symlink/db-wal protection, constant-time hash comparison, and audit log encoding | none | DONE |
| 2 | Backend Thread Safety, 503 Non-Mocking & Routing | Enforce Bukkit main thread hopping (`executor.supply`), 504 `main_thread_timeout`, 503 `bukkit_unavailable` on headless controllers, clean error codes, unregister package install, fix Javalin static fallback, remove root scratch files | M1 | DONE |
| 3 | Frontend Data Fetching, Routing & Token Lifecycle | Fix player JSON parsing, world action serialization, memory token usage in files/backups, replace `<a href>` with Next `<Link>`, add `/dashboard/map`, fix WS token reconnect | none | DONE |
| 4 | Frontend Design System, A11y & Error Boundaries | Add `.linear-panel`, `.tracking-heading`, color CSS variables in `globals.css`, add `error.tsx` and `global-error.tsx`, improve slot accessibility and form labels | M3 | DONE |
| 5 | Full Verification, Adversarial Hardening & Final Report | Add automated test coverage for invariants, run `./gradlew :plugin:test` and `npm run build`, execute challenger and forensic audits, publish `audit_results.md` | M1, M2, M3, M4 | DONE |

---

## Interface Contracts

### Backend ↔ Frontend Auth & Session Contract
- Access tokens are transmitted in HTTP `Authorization: Bearer <token>` headers.
- Access tokens are stored ONLY in memory (`memoryAccessToken` in `lib/api.ts`), NEVER in `localStorage` or `sessionStorage`.
- Refresh tokens are transmitted strictly via `HttpOnly`, `SameSite=Strict`, `Secure` cookies scoped to `/api/auth`.
- Frontend file and backup downloads use authenticated blob streams (`fetchApi`) or authorized temporary tokens, never raw query parameter token leakage.

### Backend ↔ Paper Bukkit Thread Safety Contract
- Off-thread requests (Javalin/Jetty threads) must NEVER invoke Bukkit world, chunk, player, or inventory methods directly.
- All game state queries and mutations must execute via `ThreadExecutor.supply(Callable<T>)`.
- If the callable times out (default 3000ms), return HTTP 504 with JSON body `{"error": "main_thread_timeout"}`.
- If `Bukkit.getServer() == null` (headless/testing), mutating endpoints must return HTTP 503 with JSON body `{"error": "bukkit_unavailable"}`.

### SQLite Transaction Contract
- All read operations via `ResultSet` must materialize their records into memory before any mutating statement or nested connection is executed.
- `PRAGMA journal_mode = WAL` and `PRAGMA busy_timeout = 5000` must be maintained on every connection.

---

## Code Layout
- `plugin/src/main/java/dev/lukka/oculus/DashboardController.java`: System and player stats controllers
- `plugin/src/main/java/dev/lukka/oculus/auth/`: Authentication service, token rotation, user repository
- `plugin/src/main/java/dev/lukka/oculus/audit/`: Audit logging and hash chain verification
- `plugin/src/main/java/dev/lukka/oculus/files/`: File jail sandbox, upload/download controllers
- `plugin/src/main/java/dev/lukka/oculus/bootstrap/`: IP resolution, rate limiting, Javalin server setup
- `plugin/src/main/java/dev/lukka/oculus/players/`: Player inventory and PDC controllers
- `plugin/src/main/java/dev/lukka/oculus/packages/`: Package management controller
- `plugin/src/main/java/dev/lukka/oculus/integrations/apollo/`: Apollo waypoint and player integration
- `plugin/src/test/java/dev/lukka/oculus/`: JUnit 5 test suites and adversarial stress harnesses
- `web/app/`: Next.js App Router pages and layouts
- `web/lib/`: Frontend API clients, token storage, and WebSocket managers
- `web/components/`: Reusable React components
