<div align="center">
  <h1>Oculus</h1>
  <p><strong>High-Performance Operations Console for Minecraft Servers</strong></p>

  [![CI](https://github.com/Lukk1a/OculusMC/actions/workflows/ci.yml/badge.svg)](https://github.com/Lukk1a/OculusMC/actions/workflows/ci.yml)
  [![CodeQL](https://github.com/Lukk1a/OculusMC/actions/workflows/codeql.yml/badge.svg)](https://github.com/Lukk1a/OculusMC/actions/workflows/codeql.yml)
  [![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
</div>

<br />

Oculus is a next-generation control panel designed as a drop-in Bukkit plugin. It embeds a lightweight Javalin web server directly into the Minecraft server process, serving a statically exported Next.js (App Router) dashboard. This eliminates the need for external panel infrastructure, daemon processes, or databases while providing real-time telemetry and management capabilities.

## Architecture

* **Backend (`plugin/`)**: A Bukkit/Paper plugin running an embedded Javalin web server on a background thread. All interactions with the Minecraft world state are strictly marshalled to the main server thread to prevent concurrency issues and ensure safety.
* **Frontend (`web/`)**: A Next.js 14 App Router application, styled with Tailwind CSS v4 and `shadcn/ui`. The app is statically exported (`output: 'export'`) and bundled directly into the plugin's `resources` directory, served from the classpath by Javalin.
* **Security**: Zero security theater. Features strict IP rate-limiting, hardened PreAuth tokens, Secure HttpOnly refresh cookies, TOTP 2FA, and strict canonical path verification to prevent SSRF and path traversal within the host environment.

## Features

- ⚡ **Zero-Infra Setup**: Just drop the `.jar` into your `plugins` folder.
- 📊 **Real-time Telemetry**: TPS, memory usage, CPU, and online player counts.
- 👥 **Player Management**: Live 3D skin rendering, inventory inspection, kicking, banning, and PDC editing.
- 🗺️ **World & Map Viewer**: Live dynamic map integrations directly in the dashboard.
- 💾 **Automated Backups**: Trigger and download server backups securely.
- 🛡️ **Enterprise Security**: Built-in 2FA (TOTP), rotating token families, and strict thread safety guarantees.

## Getting Started

### Prerequisites

* Java 21 (for compiling the Bukkit plugin)
* Node.js 20+ (for building the Next.js frontend)

### Building for Production

Oculus uses Gradle to build the plugin and shadow the dependencies. The Next.js frontend must be built and exported before compiling the Java plugin.

1. **Build the Next.js Frontend:**
   ```bash
   cd web
   npm install
   npm run build
   cd ..
   ```
   *Note: This generates the static site payload in the `web/out` directory.*

2. **Build the Bukkit Plugin:**
   ```bash
   ./gradlew :plugin:shadowJar
   ```

3. **Install:**
   Copy the generated artifact `plugin/build/libs/plugin-1.0.0-SNAPSHOT-all.jar` to your server's `plugins` folder and restart the server.

### Configuration

By default, the web interface is bound to port `8080`.
The default configuration file (`plugins/Oculus/config.yml`) will be generated on the first run, where you can modify the bind address, port, and security policies.

## Development

During development, you can run the Next.js development server to interact with a live backend (running on localhost).

```bash
cd web
npm run dev
```

*Note: The Next.js development server runs on `http://localhost:3000` but expects the plugin backend to be running concurrently on `http://localhost:8080` for API routes.*

## Security Invariants

When contributing to this repository, adhere to the strict security rules documented in [AGENTS.md](AGENTS.md):
- **Real Authorization**: No mock or stub authorization.
- **Thread Safety**: Never mutate Bukkit state from Javalin threads without jumping to the primary server thread.
- **Jail Enforcement**: All file I/O must remain contained within the server's world container path.

## License

MIT
