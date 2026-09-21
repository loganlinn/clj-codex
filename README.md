A data-oriented SDK to use [Codex app-server](https://learn.chatgpt.com/docs/app-server) from Clojure or [Babashka](https://github.com/babashka/babashka).

The library provides explicit connections, immutable conversation values, an inspectable operation catalog, and domain functions.
It supports stdio, TCP WebSockets, and WebSockets over filesystem Unix sockets.

## Usage

Add this checkout as a local dependency in your application's `deps.edn` or `bb.edn`:

```clojure
{:deps {com.github.loganlinn/clj-codex
        {:local/root "/path/to/clj-codex"}}}
```

Start with the [REPL tour](examples/repl.clj) or choose a runnable script from the [examples directory](examples/README.md).
The examples cover connections, server state, conversation history, turns, events, and code reviews.
Their README includes runtime requirements, server setup, authentication, and CLI options.

## Reference

- [API reference](API.md): public namespaces, functions, arguments, and docstrings.
- [API source](src/codex): implementations and namespace documentation.
- [Protocol schemas](apis/codex/app-server): bundled app-server wire definitions.
- [Operation catalog](resources/codex/catalog.edn): operation metadata for discovery and invocation.
- [Design notes](DESIGN.md): original architecture and contracts.

The bundled protocol does not guarantee support from every server version.
Use [`codex.api`](API.md#codex.api) to inspect protocol provenance and available operations.
Experimental operations require capability opt-in and server support.

## Development

List the available tasks with `bb tasks`.
If you use mise, prefix commands with `mise exec --`.

| Command | Purpose |
| --- | --- |
| `bb fmt:check` | Check Clojure formatting without changes |
| `bb fmt` | Fix Clojure formatting |
| `bb test` | Run the shared tests in Babashka |
| `clojure -M:test` | Run the shared tests in JVM Clojure |
| `bb quickdoc` | Generate `API.md` from public docstrings |
| `bb codegen` | Regenerate protocol schemas and the operation catalog with the installed `codex` binary |
| `bb test:unix-live` | Run the opt-in smoke test against a local Codex app-server |
| `bb jar v0.1.0` | Build a library JAR and POM without publishing |
| `bb install v0.1.0` | Build and install the library in the local Maven repository |
| `bb release:check v0.1.0` | Check that the release tag points to the clean checkout |
| `bb publish v0.1.0` | Build and publish a tagged version to Clojars |
| `bb jar 0.1.0-SNAPSHOT` | Build a snapshot JAR and POM without publishing |
| `bb publish:snapshot 0.1.0-SNAPSHOT` | Publish a snapshot from a clean checkout without a Git tag |
| `bb test:build` | Check release and snapshot publication paths without uploading |
| `bb clean` | Delete build output |

Task definitions are in [bb.edn](bb.edn), JVM dependencies in [deps.edn](deps.edn), and formatting rules in [.cljfmt.edn](.cljfmt.edn).
After changes to public docstrings, run `bb quickdoc` and commit `API.md` with the source changes.

[CI](.github/workflows/ci.yml) checks formatting and runs the shared tests on Linux and macOS.
It uses Babashka 1.12.218 and Clojure 1.12.0 on Java 21.
The shared tests use local peers and require no Codex account or server.
The full suite requires Unix socket support and fails if that support is unavailable.

The separate live smoke test requires `codex` on `PATH`.
It uses an isolated temporary Codex home and does not run a model or access existing sessions.

## Releases

The separate [release workflow](.github/workflows/release.yml) publishes to Clojars when a maintainer publishes a GitHub Release.
The release tag sets the Maven version: `v0.1.0` becomes `com.github.loganlinn/clj-codex` version `0.1.0`.
The manual [snapshot workflow](.github/workflows/snapshot.yml) publishes versions such as `0.1.0-SNAPSHOT` from `main`, without a Git tag or GitHub Release.
See the [release guide](docs/releasing.md) for account setup, credentials, release commands, and recovery steps.
The [research notes](docs/releasing-research.md) explain the design and link to primary sources and Babashka examples.

## License

[MIT](LICENSE). The private Unix WebSocket implementation includes its own [license](src/codex/impl/websocket/LICENSE) and [provenance](src/codex/impl/websocket/PROVENANCE.md).
