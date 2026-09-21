# clj-codex

A Clojure SDK for [codex app-server](https://learn.chatgpt.com/docs/app-server) API.

## Usage

Clone the repository:

```sh
git clone https://github.com/loganlinn/clj-codex.git
```

Replace `/path/to/clj-codex` in the examples with the absolute path to your checkout.

### Clojure (`clj`)

Start a JVM Clojure REPL with the Clojure CLI:

```sh
clj -Sdeps '{:deps {com.github.loganlinn/clj-codex {:local/root "/path/to/clj-codex"}}}'
```

From the SDK checkout, run `clj` without `-Sdeps`.
The repository's `deps.edn` supplies the source paths and dependencies.

### Babashka (`bb`)

Start a REPL with Babashka 1.12.218 or later:

```sh
bb -Sdeps '{:deps {com.github.loganlinn/clj-codex {:local/root "/path/to/clj-codex"}}}'
```

From the SDK checkout, run `bb repl`.
The repository's `bb.edn` supplies the source paths.

### Project dependency

Add this dependency to your application's `deps.edn` for Clojure, or `bb.edn` for Babashka:

```clojure
{:deps {com.github.loganlinn/clj-codex
        {:local/root "/path/to/clj-codex"}}}
```

### Getting Started

See the [examples README](examples/README.md) for setup instructions, a REPL tour, and runnable scripts.
The examples cover connections, server state, conversation history, turns, events, and code reviews.

## Reference

- [API reference](API.md): public namespaces, functions, arguments, and docstrings.
- [API source](src/codex): implementations and namespace documentation.
- [Protocol schemas](apis/codex/app-server): bundled app-server wire definitions.
- [Operation catalog](resources/codex/catalog.edn): operation metadata for discovery and invocation.
- [Agent instructions](AGENTS.md): architecture, contracts, and development guidance.

The bundled protocol does not guarantee support from every server version.
Use [`codex.api`](API.md#codex.api) to inspect protocol provenance and available operations.
Experimental operations require capability opt-in and server support.
