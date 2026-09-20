# Codex app-server for Clojure and Babashka

A data-oriented SDK for local and remote Codex app-server connections.

The library provides explicit connections, immutable conversation values, an inspectable operation catalog, and domain functions. It supports stdio, TCP WebSockets, and WebSockets over filesystem Unix sockets.

The bundled protocol comes from Codex CLI **0.155.1**. It includes 164 client operations, their experimental fields, server requests, and notifications. Experimental calls require capability opt-in. The server can still reject a method that its runtime does not support.

## Use the library

For local development, add this checkout as a dependency:

```clojure
;; deps.edn or bb.edn in your application
{:deps {io.github.loganlinn/codex-app-server-repl
        {:local/root "/path/to/codex-app-server-repl"}}}
```

JVM dependencies are in `deps.edn`. Babashka includes the required runtime libraries. The test matrix currently covers Babashka 1.12.218 and Clojure 1.12.0 on Java 21.

```clojure
(require '[codex.app-server :as server]
         '[codex.thread :as thread]
         '[codex.turn :as turn]
         '[codex.input :as input]
         '[codex.item :as item]
         '[codex.run :as run]
         '[codex.repl :as repl])

(def conn
  (server/connect!
    {:transport {:type :stdio
                 :command ["codex" "app-server"]}
     :client-info {:name "my-clojure-client" :version "0.1.0"}}))

(def context (thread/start! conn {:cwd "/workspace/project"}))
(def thread-id (get-in context [::thread/thread ::thread/id]))
(def watch (repl/watch! conn {:thread-id thread-id}))

(def work
  (run/start! conn thread-id
    {:input [(input/text "Explain the repository structure.")]}))

(run/snapshot work)
(repl/pending conn)

;; This wait does not interrupt the turn after 60 seconds.
(def result (run/await! work 60000 ::still-running))

(when (map? result)
  (item/messages (::turn/items result)))

(repl/unwatch! watch)
(run/close! work)
(server/close! conn)
```

Use `try`/`finally` to close resources in application code. Loading namespaces does not start a connection or print events.

The server uses its configured Codex account. Connecting does not perform a login. Model generation requires an authenticated or otherwise configured provider.

## Connect to a remote server

```clojure
(def conn
  (server/connect!
    {:transport {:type :websocket
                 :url "wss://codex.example.com/"
                 :token-fn #(System/getenv "MY_CODEX_TRANSPORT_TOKEN")}
     :client-info {:name "my-clojure-client" :version "0.1.0"}
     :capabilities {:experimental-api true}}))
```

The token function supplies the WebSocket handshake credential. Account login is a separate API. Upstream labels the WebSocket transport experimental.

Connections own only subprocesses they start. Closing a remote connection does not terminate its server or interrupt its turns. Reconnection and operation replay are explicit.

## Connect through a Unix socket

Start an app-server with an explicit socket path:

```sh
codex app-server --listen unix:///tmp/codex.sock
```

```clojure
(def conn
  (server/connect!
    {:transport {:type :websocket
                 :url "ws://localhost/"
                 :unix-socket "/tmp/codex.sock"
                 :connect-timeout-ms 10000}}))

(server/await! (server/request! conn "thread/loaded/list" {}))
(server/close! conn)
```

The runnable [Unix socket example](examples/unix_socket.clj) uses this configuration to inspect an existing server:

```sh
bb examples/unix_socket.clj /tmp/codex.sock
```

`:unix-socket` selects the filesystem socket. `:url` supplies the HTTP Upgrade host, path, and query; it does not cause DNS lookup or a TCP connection. Unix connections support `ws://` only. TLS, proxies, and compression are not supported by the private Unix backend.

Unix transport requires Java 16+ with operating-system Unix socket support, or a compatible Babashka runtime. It is tested on macOS with Java 21 and Babashka 1.12.218. The private facade loads lazily, so loading the SDK does not load Java 16 Unix classes. TCP WebSockets retain the upstream `org.babashka/http-client` dependency.

`:headers` and `:token-fn` work as they do for TCP WebSockets. `:connect-timeout-ms` is a positive integer and defaults to 10000. It covers socket connection and HTTP Upgrade, and bounds each send wait. Initialization RPCs use the connection's `:request-timeout-ms`.

Closing the client leaves the external server and its socket path intact. Malformed JSON or binary messages fail the connection and its pending calls. Both WebSocket backends assemble text fragments before JSON parsing; the SDK currently has no complete-message size limit.

Run `bb test` or `clojure -M:test` for TCP, stdio, Unix protocol, and SDK regression tests. The loading check runs first in a fresh process. Use `bb -m codex.test-runner --loading-only` or `clojure -M:test --loading-only` to run only that check. Full suites fail if Unix sockets are unavailable instead of silently skipping coverage. The JVM `http-kit` dependency is test-only.

Run `bb test:unix-live` for the opt-in live Codex smoke test. It uses an isolated temporary Codex home, performs initialization and a read-only RPC, verifies reconnect after client disposal, and cleans up its own process and directory. It does not run a model or access existing sessions.

The private module's [provenance](src/codex/impl/websocket/PROVENANCE.md) and [license](src/codex/impl/websocket/LICENSE) accompany its source.

## Discover operations and schemas

```clojure
(require '[codex.api :as api]
         '[codex.schema :as schema])

(api/provenance)
(api/operations)
(api/operations :experimental?)
(api/describe :thread/read)
(schema/describe "ThreadReadParams")

(api/invoke! conn
  {:op :thread/read
   :args {:thread-id thread-id :include-turns true}})

(def pending
  (api/submit! conn
    {:op :model/list :args {:limit 10}}
    {:timeout-ms 10000}))

(api/await! pending 1000 ::waiting)
```

Operation IDs use kebab-case, with intermediate wire segments in the keyword namespace. For example, `thread/goal/set` becomes `:thread.goal/set`.

The catalog contains every exported client operation. Curated namespaces provide convenient domain functions rather than a generated function for every method.

| Namespace | Purpose |
| --- | --- |
| `codex.app-server` | Connections, raw RPCs, raw observers, server replies |
| `codex.api`, `codex.schema` | Catalog, invocation, schemas, reducible pages |
| `codex.thread`, `codex.turn`, `codex.history` | Conversation lifecycle and history |
| `codex.input`, `codex.item` | Pure input constructors and item queries |
| `codex.event`, `codex.interaction`, `codex.run` | Events, server requests, tracked turns |
| `codex.goal`, `codex.review` | Goals and code reviews |
| `codex.command`, `codex.process`, `codex.fs` | Execution and server-side files |
| `codex.model`, `codex.skill`, `codex.app`, `codex.plugin`, `codex.mcp` | Discovery and integrations |
| `codex.config`, `codex.permission`, `codex.account` | Configuration, permission values, authentication |
| `codex.repl` | Bounded watchers and inspection |

`schema/valid?` and `schema/explain` check **wire JSON values** against the structural vocabulary in the bundled schemas. They are not general-purpose JSON Schema validators.

## Data conventions

- Domain entity keys are qualified, such as `:codex.thread/id` and `:codex.turn/status`.
- Operation arguments use unqualified kebab-case keys.
- Known enum values use keywords. IDs, model names, paths, and cursors remain strings.
- Omitted keys stay omitted. A present `nil` encodes JSON null.
- Arbitrary JSON, including tool arguments and output schemas, keeps string keys.
- Unknown response fields appear in `:codex.api/extensions` with their original names.
- Unknown tagged variants retain their payload under `:codex.api/raw`.

The codec follows schema boundaries. It does not recursively rename user-owned JSON.

```clojure
{:output-schema
 {"type" "object"
  "properties" {"answerText" {"type" "string"}}
  "required" ["answerText"]
  "additionalProperties" false}}
```

Thread creation, resumption, and forking return a context with `::thread/thread`, `::thread/config`, and `::thread/instruction-sources`. Reading returns a thread snapshot. Starting a turn returns its initial snapshot. `run/await!` waits for completion and returns the observed final turn.

Paths belong to the server or execution environment. The SDK does not resolve them against the local client directory.

## Handle server requests

```clojure
(require '[codex.interaction :as interaction])

;; Install before starting work that can require approval.
(def registration
  (interaction/handle! conn
    :item.command-execution/request-approval
    (fn [_request] ::interaction/defer)))

(def request (first (interaction/pending conn)))

;; After inspecting the actual request:
(interaction/respond! conn request {:decision :decline})

(interaction/unhandle! registration)
```

A handler returns typed response data or `::interaction/defer`. One handler owns each request kind. Requests carry connection-scoped reply tokens. A remote resolution invalidates the token.

Unhandled approvals default to decline. Unsupported server calls receive an RPC error. Deferred requests expire after `:interaction-timeout-ms`, which defaults to five minutes.

For initialization-time requests, `connect!` accepts `:handlers`. These raw handlers map wire method strings to functions returning `{:result wire-data}`, `{:error wire-error}`, or `:codex.app-server/defer`.

## Observe events and reduce state

```clojure
(require '[codex.event :as event])

(def state (atom {}))
(def subscription
  (event/listen! conn {:thread-id thread-id}
    #(swap! state event/apply-event %)))

(event/turn-snapshot @state thread-id "turn-id")
(event/unlisten! subscription)
```

Each listener has an ordered worker queue. Slow observers do not block RPC replies. Overflow closes the affected observer and calls its `:on-error` callback. The default queue capacity is 1024 messages.

Completed item snapshots replace provisional delta content. Turn completion does not erase previously observed items. A local event listener does not create a remote thread subscription.

Tracked runs install observation before submission and handle completion-before-response races. They require a connection without notification opt-outs. They currently accept ordinary user input. Standalone tool output remains available through `turn/start!`.

## Reduce paginated results

```clojure
(into []
  (comp (map ::thread/id) (take 10))
  (api/entries conn {:op :thread/list :args {:limit 25}}))
```

`api/pages` and `api/entries` return reducibles. Construction and printing perform no requests. Reduction stops before another page after `reduced`.

## Execute commands

```clojure
(require '[codex.command :as command])

(command/exec! conn
  {:command ["git" "status" "--short"]
   :cwd "/workspace/project"})

(def process
  (command/start! conn
    {:command ["some-program"] :cwd "/workspace/project" :stream-stdin true}
    {:capture-limit-bytes 1048576
     :on-output (fn [{:keys [stream bytes]}]
                  ;; Consume bytes with a streaming decoder or byte-oriented sink.
                  nil)}))

(command/write! process (.getBytes "input\n" "UTF-8") true)
(command/await! process 5000 ::running)
```

Buffered execution returns strings. Streaming execution returns byte arrays and `:truncated?` alongside the exit status. UTF-8 characters can span chunks. Decode incrementally or decode the complete byte array after completion.

`codex.process` provides explicit unsandboxed execution and requires experimental capability opt-in. `thread/shellCommand` is also unsandboxed and remains a distinct catalog operation.

## Errors and cleanup

RPC, transport, schema, lifecycle, and deadline failures throw `ex-info` with `:codex.error/category`. RPC errors retain their wire code, message, and data. Failed turns and nonzero command exit codes remain result data.

A local wait timeout does not cancel anything. RPC deadline expiry leaves the remote outcome unknown. Use `turn/interrupt!`, `command/terminate!`, or `process/kill!` for explicit control. `server/abandon!` releases only local pending state.

There are no automatic retries. Connection info and handle printing omit credentials. Raw results and explicit REPL event watchers can contain sensitive application data.

## Development

List the available tasks with `bb tasks`. If you use mise, run these commands through `mise exec --`.

```sh
bb test
clojure -M:test

# Fix formatting, or check it without changing files.
bb fmt
bb fmt:check

# Generate the API reference from public namespace docstrings.
bb quickdoc

# Regenerate both schema variants, provenance, and the operation catalog.
bb codegen
```

The formatter uses [cljfmt](https://github.com/weavejester/cljfmt) with the repository configuration in `.cljfmt.edn`.
It covers source, tests, examples, scripts, and root EDN configuration files.
Pass file or directory arguments to limit its scope, for example `bb fmt src/codex/thread.clj`.
`bb fmt:check` exits with a nonzero status when files need formatting.

The [API reference](API.md) uses [Quickdoc](https://github.com/borkdude/quickdoc), as do Babashka libraries such as `fs` and `http-client`.
It includes public namespaces in `src/codex` and excludes `codex.impl.*`.
After changes to public docstrings, run `bb quickdoc` and commit `API.md` with the source changes.
The task accepts Quickdoc options, for example `bb quickdoc --outfile target/API.md --toc false`.

The shared tests use deterministic peers and need no OpenAI account. They cover real subprocess pipes and loopback WebSockets, including fragmented frames and authentication headers.

Schema regeneration uses the installed `codex` binary. Commit the generated schema files, catalog, stable baseline, and schema index together.

See [DESIGN.md](DESIGN.md) for the original architecture and contracts. This implementation uses explicit `await!` functions. Handles are not public promise or channel implementations. Catalog schema identifiers use the exported schema names.
