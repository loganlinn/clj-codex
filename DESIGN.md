# Clojure SDK design for Codex app-server

Approved design, 2026-09-18. The implementation is in `src/codex`. See [README.md](README.md) for current usage, tested runtimes, and implementation details.

The library exposes immutable domain values, operations represented as data, and explicit connections that perform effects. A small domain API supports common workflows. A versioned operation catalog provides protocol coverage without a generated function for every endpoint.

`codex` is the provisional namespace prefix. The important choice is the separation of responsibilities beneath it.

## Evidence and scope

I read the complete [app-server documentation](https://learn.chatgpt.com/docs/app-server), including the API overview, events, approvals, execution, configuration, and authentication sections.

The repository contains 312 JSON Schema files. Its top-level unions describe 102 client requests, 10 server requests, and 82 server notifications. These counts come from [ClientRequest.json](apis/codex/app-server/ClientRequest.json), [ServerRequest.json](apis/codex/app-server/ServerRequest.json), and [ServerNotification.json](apis/codex/app-server/ServerNotification.json).

The schema snapshot and live documentation differ. For example, the snapshot includes attachment and section operations. The documentation describes process and environment requests absent from its `ClientRequest` union. Some corresponding notifications and response schemas exist independently. The current schema snapshot has no recorded Codex version.

The design therefore separates three things:

- Protocol facts from a specific schema snapshot.
- Curated SDK contracts with explicit semantics.
- Availability on the connected server, including experimental capabilities.

Schema presence alone does not establish runtime availability. The initial implementation must record the generator version and schema digest.

## The conceptual model

The protocol has several different kinds of things. Treating all of them as resources with CRUD methods obscures their behavior.

| Kind | Examples | Clojure representation |
| --- | --- | --- |
| Owned runtime resource | Connection, subscription, pending RPC, running command | Small opaque handle with explicit lifecycle |
| Entity snapshot | Thread, turn, item, goal, model, app, plugin | Immutable map |
| Reference | Thread ID, turn ID with thread ID, process ID with connection ID | String ID or small qualified map |
| Tagged value | User input, review target, sandbox policy, approval decision | Map with a known tag |
| Query | Thread search, model discovery, history page | Operation map plus filters |
| Command | Start turn, change configuration, archive thread | Operation map with explicit effect metadata |
| Event | Item delta, completed turn, changed skill catalog | Immutable envelope plus typed payload |
| Server request | Approval, elicitation, tool invocation, token refresh | Pending request data with a connection-scoped reply token |
| Workflow | Run to turn completion, login ceremony, streamed command | Composition with explicit observation and cleanup |

Conversation containment is thread → turns → items. Input values describe what to submit. Items describe recorded work and output. These are separate schema families.

Thread identity, session identity, fork ancestry, and spawned-agent ancestry remain separate attributes. A thread snapshot carries no connection and performs no I/O.

The broader taxonomy includes:

- Conversation: threads, turns, items, goals, attachments, history, sections, review targets, plans, diffs, and token usage.
- Execution: sandboxed commands, explicit unsandboxed processes, thread shell commands, environments, filesystem paths, and watches.
- Interaction: approvals, permission grants, questions, MCP elicitation, dynamic tools, authentication refresh, and attestation.
- Discovery: models, provider capabilities, skills, hooks, apps, plugins, marketplaces, MCP tools, and MCP resources.
- Administration: effective configuration, configuration layers, requirements, permission profiles, runtime features, account state, limits, and imports.

There is no universal `Entity` protocol. A model catalog entry, an approval, and a turn have different useful operations.

## Namespace organization and public vars

The tables list the intended public functions. They are a namespace plan, not a requirement to publish every convenience function in the first release.

### Connection and operation foundation

| Namespace | Public vars | Responsibility |
| --- | --- | --- |
| `codex.app-server` | `connect!`, `close!`, `status`, `info`, `request!`, `notify!`, `listen!`, `unlisten!`, `reply!`, `pending-requests`, `await!`, `abandon!` | Transport, initialization, request correlation, raw messages, connection lifecycle |
| `codex.api` | `operations`, `describe`, `invoke!`, `submit!`, `await!`, `pages`, `entries` | Operation catalog, argument conversion, result conversion, pagination |
| `codex.schema` | `schemas`, `describe`, `valid?`, `explain` | Inspectable schema registry and optional runtime checks |
| `codex.event` | `listen!`, `unlisten!`, `matches?`, `apply-event`, `text-delta`, `terminal?` | Domain events, filters, pure projection functions |
| `codex.interaction` | `pending`, `handle!`, `unhandle!`, `respond!`, `reject!`, `resolved?` | Server requests and exactly-once local reply ownership |
| `codex.repl` | `watch!`, `unwatch!`, `inspect`, `history`, `pending`, `explain` | Bounded observation, formatting, and exploration |

Keep framing, JSON codecs, executors, generated indexes, and pending-request tables under `codex.impl.*`. Transport adapters remain internal until a real extension use case needs a public SPI.

### Conversation API

| Namespace | Public vars | Responsibility |
| --- | --- | --- |
| `codex.thread` | `start!`, `resume!`, `fork!`, `read!`, `list!`, `loaded!`, `rename!`, `patch!`, `archive!`, `restore!`, `delete!`, `unsubscribe!`, `ref` | Conversation lifecycle and identity |
| `codex.turn` | `start!`, `steer!`, `interrupt!`, `terminal?`, `ref` | Turn control and pure status predicates |
| `codex.history` | `turns!`, `items!`, `compact!`, `inject!` | Stored history and explicit context changes |
| `codex.item` | `text`, `messages`, `commands`, `changes`, `completed?` | Pure queries over item data |
| `codex.input` | `text`, `image`, `local-image`, `audio`, `local-audio`, `skill`, `mention`, `normalize` | Pure input constructors |
| `codex.goal` | `read!`, `set!`, `clear!`, `remaining-tokens` | Goal commands and pure accounting |
| `codex.review` | `start!`, `uncommitted`, `branch`, `commit`, `custom` | Review workflow and pure target constructors |
| `codex.run` | `start!`, `await!`, `snapshot`, `interrupt!`, `close!` | Track one turn from submission through terminal events |

`history/compact!` returns the acknowledgement. It does not claim that compaction finished. Deprecated rollback stays accessible through the catalog, with deprecation metadata.

Attachments and sections initially use catalog operations. Frequent usage can later justify curated `codex.attachment` and `codex.section` APIs.

### Execution, discovery, and administration

| Namespace | Public vars | Responsibility |
| --- | --- | --- |
| `codex.command` | `exec!`, `start!`, `write!`, `resize!`, `terminate!`, `await!` | Sandboxed command execution, buffered or streamed |
| `codex.process` | `start!`, `write!`, `resize!`, `kill!`, `await!` | Experimental unsandboxed process control |
| `codex.fs` | `read!`, `write!`, `stat!`, `list!`, `mkdir!`, `copy!`, `delete!`, `watch!`, `unwatch!` | Files on the server execution host |
| `codex.model` | `list!`, `capabilities!` | Model and provider discovery |
| `codex.skill` | `list!`, `enable!`, `disable!`, `extra-roots!` | Skill discovery and configuration |
| `codex.app` | `list!`, `read!`, `installed!` | Connector metadata and runtime availability |
| `codex.plugin` | `list!`, `read!`, `install!`, `uninstall!`, `skill!` | Version-gated plugin operations |
| `codex.mcp` | `servers!`, `resource!`, `call!`, `login!`, `reload!` | MCP integration through app-server |
| `codex.config` | `read!`, `requirements!`, `write!`, `patch!` | Effective configuration and persistent edits |
| `codex.permission` | `profiles!`, `read-only`, `workspace-write`, `external-sandbox`, `grant` | Permission discovery and pure policy values |
| `codex.account` | `read!`, `login!`, `cancel-login!`, `logout!`, `limits!`, `usage!` | Account state and authentication workflows |

The catalog also covers hooks, marketplaces, feature controls, environments, background terminals, imports, Windows setup, feedback, and account administrative actions. Their discovery path is `api/operations` and `api/describe`.

Keep `thread/shellCommand` visibly distinct in the catalog. Never implement it as a fallback for sandboxed command execution.

Preserve experimental and under-development labels in discovery results. Plugin functions do not imply production readiness.

Command execution needs two contracts. `command/exec!` waits for a buffered result. `command/start!` submits streaming execution and immediately returns a handle with a client-assigned process ID. Its RPC remains pending until execution finishes. Output, stdin, resize, and termination use that handle concurrently.

`process/start!` instead tracks its acknowledgement and later exit notification. Both APIs preserve bytes across output chunks. Text decoding is an explicit view with incremental UTF-8 handling.

## Calling convention

All external I/O functions end in `!`, including reads. Pure constructors and queries have no suffix. Connections are explicit first arguments.

```clojure
(thread/read! conn thread-id {:include-turns true})
(thread/fork! conn thread-id {:last-turn-id turn-id})
(turn/start! conn thread-id {:input [(input/text "Explain this code.")]})
(turn/steer! conn turn-ref [(input/text "Focus on concurrency.")])
```

Use positional arguments for connection and identity. Use a trailing map for optional arguments. Constructors return ordinary maps that callers can build directly.

Accept an ID or an explicit reference where the identity is unambiguous. A turn reference contains both IDs:

```clojure
{::thread/id "thr_123"
 ::turn/id   "turn_456"}
```

`turn/steer!` encodes the turn ID as `expectedTurnId`. It never discovers an active turn and silently substitutes that identity.

Ordinary domain functions block until their RPC response arrives. They do not wait for asynchronous work to finish. `run/await!` provides that separate operation. Explicit workflow starters such as `command/start!` return handles under their documented contracts.

For concurrent calls, the catalog exposes a single asynchronous entry point:

```clojure
(def pending
  (api/submit! conn
    {:op :thread/read
     :args {:thread-id "thr_123" :include-turns true}}
    {:timeout-ms 10000}))

(api/await! pending 1000 ::still-waiting)
```

The request timeout and the local wait timeout differ. When its local wait expires, `api/await!` leaves the pending operation intact.

Avoid generating synchronous, asynchronous, callback, and channel variants for each domain operation. The generic execution functions cover these composition needs.

## Operations as inspectable data

An operation invocation contains no connection, callback, promise, or generated class:

```clojure
{:op :thread/start
 :args {:cwd "/workspace/project"
        :model "a-model-id-from-discovery"}}
```

The descriptor is a separate value:

```clojure
{:op :thread/start
 :wire/method "thread/start"
 :direction :client->server
 :args-schema :codex.schema/thread-start-args
 :result-schema :codex.schema/thread-context
 :completion :rpc-response
 :effects #{:thread/create :thread/subscribe}
 :retry :never-automatically
 :availability {:schema-revision "recorded-at-generation"}}
```

`api/describe` exposes the descriptor, documentation, examples, required fields, experimental fields, and deprecation information. The catalog is enumerable data.

`api/invoke!` runs an operation map synchronously. Domain functions assemble those maps and apply documented domain semantics. They use the same codecs and return contracts.

Generate wire descriptors and schema indexes. Hand-author semantic annotations, public names, and workflows. Generation does not infer idempotency or stability from method names.

For unsupported future methods, `app-server/request!` accepts a literal wire method and string-keyed JSON data. It bypasses domain conversion explicitly.

The catalog necessarily records individual RPCs. The curated library adds value through domain references, schema-aware data, reducers, and workflows. Its namespace structure does not mirror URL segments.

## Values, schemas, and lossless conversion

Use qualified keys for domain entities and references. The examples use aliases for their owning namespaces:

```clojure
{::thread/id "thr_123"
 ::thread/session-id "session_abc"
 ::thread/name "Concurrency investigation"
 ::thread/status {:type :active :active-flags #{:waiting-on-approval}}
 ::thread/turns
 [{::turn/id "turn_456"
   ::turn/status :in-progress
   ::turn/items
   [{::item/id "item_789"
     ::item/type :agent-message
     ::item/text "I found two relevant functions."}]}]}
```

Operation argument maps use unqualified kebab-case keys because their operation supplies the context. Small tagged values use `:type` with schema-defined keyword tags.

```clojure
(input/text "Summarize this file.")
;; => {:type :text :text "Summarize this file."}

(review/branch "main")
;; => {:type :base-branch :branch "main"}
```

`input/normalize` accepts a string, one input map, or an ordered collection of input maps. It always returns a vector. It never stringifies arbitrary objects or changes item order. Standalone tool output has a separate schema and never becomes user text implicitly.

Keep IDs, model names, tool names, provider names, paths, cursors, and user text as strings. Convert only known protocol enums to keywords.

Conversion follows schemas and explicit mappings. A recursive camel-case-to-kebab-case walk cannot preserve arbitrary JSON correctly.

Preserve user-owned JSON keys in tool arguments, tool results, output schemas, injected Responses items, and arbitrary configuration values. These values use string-keyed maps.

```clojure
{:output-schema
 {"type" "object"
  "properties" {"answerText" {"type" "string"}}
  "required" ["answerText"]
  "additionalProperties" false}}
```

Missing keys mean omitted fields. Present `nil` means explicit JSON null, subject to that field's schema. Never remove all nil values from outgoing data.

Unknown object fields remain in `:codex.api/extensions`, keyed by their original wire names. Unknown union variants remain tagged opaque values with their raw payload. Unknown enum strings remain strings. Decoding newer server responses must not silently discard data.

Known public arguments receive strict checks. Extensions have an explicit string-keyed escape hatch with collision checks against encoded known fields.

Do not synthesize every default or empty collection. An unloaded item collection differs from a loaded empty collection. Preserve that distinction through presence and history-view metadata.

Keep wire timestamp units in the schema contract. Offer optional conversion functions instead of silently changing all timestamps to JVM objects.

Paths describe the relevant server or execution environment. The SDK never resolves remote paths against the client's working directory.

`codex.schema` exposes schemas for entities, arguments, results, events, and server-request replies. It does not create a namespace for every `Params` type.

Start with the checked-in JSON Schema and a small explicit mapping registry. A Malli adapter can be optional. Runtime operation must not depend on JVM-only schema code generation.

## Return values

Preserve useful response context. `thread/start!`, `resume!`, and `fork!` return a thread context:

```clojure
{::thread/thread {::thread/id "thr_123" ...}
 ::thread/config {:model "..." :cwd "/workspace/project" ...}
 ::thread/instruction-sources ["/workspace/project/AGENTS.md"]}
```

These illustrative ellipses stand for additional documented fields. The context is an immutable value, not a live session object.

`thread/read!` returns a thread snapshot. `turn/start!` returns the initial turn snapshot. `review/start!` preserves both the review thread reference and initial turn.

List operations return a page:

```clojure
{:data [...]
 :next-cursor "opaque-or-nil"
 :backwards-cursor "present-only-where-supported"}
```

`api/pages` returns an IReduceInit reducible of pages. `api/entries` flattens their data. Construction performs no I/O. Reduction requests pages and respects `reduced` before another request. There is no implicit lazy sequence that fetches more pages during REPL printing.

Acknowledgement-only operations return their documented result, often `{}`. Do not replace useful status responses with nil or a boolean.

## Low-level client-server interaction

`codex.app-server` owns the connection resource. It is usable without the domain API.

```clojure
(def conn
  (app-server/connect!
    {:transport {:type :stdio
                 :command ["codex" "app-server"]
                 :cwd "/workspace/project"}
     :client-info {:name "clojure-repl" :version "0.1.0"}
     :capabilities {:experimental-api false}}))
```

Remote connection:

```clojure
(app-server/connect!
  {:transport {:type :websocket
               :url "wss://codex.example.com/"
               :token-fn read-current-transport-token}
   :client-info {:name "clojure-client" :version "0.1.0"}})
```

Transport authentication and Codex account authentication are independent. A connection does not imply an account login.

`connect!` starts the reader and request dispatcher before initialization. It waits for the initialization response, sends `initialized`, and returns a ready connection. Initialization failures close owned resources.

The initial handler registry is part of connection arguments. This permits handlers needed during initialization and avoids a request-handling gap.

The implementation has these responsibilities:

- Frame JSONL for stdio and JSON messages for WebSockets.
- Keep process stderr separate from protocol stdout and drain it independently.
- Correlate client responses by ID, with separate handling for server requests.
- Serialize writes and support multiple concurrent pending requests.
- Register pending state before sending bytes.
- Preserve server request IDs without confusing them with client request IDs.
- Bound queues. On transport closure, reject pending calls.
- Make `close!` idempotent and expose diagnostic connection state as data.

Starting a subprocess gives the connection ownership of that subprocess. Closing a remote connection never terminates the remote server. Closing a connection does not imply turn interruption.

`request!` returns a pending handle. `await!` returns its wire result or throws a structured exception. `notify!` sends a notification without inventing a response. `reply!` sends a result or protocol error for a server request token.

Use protocols or records only for behavioral boundaries and lifecycle resources. The exact handle representation remains private. A custom printed representation exposes IDs and status without credentials or full transcripts.

No transparent reconnect or replay in the first release. A new connection requires a new handshake and explicit thread resumption. A lost connection can leave a remote operation running.

## Events, server requests, and state

Notifications and requests have different obligations. An event can be observed by many consumers. A server request needs one selected responder.

A normalized event envelope retains provenance:

```clojure
{::event/type :item/text-delta
 ::event/connection-id "conn_1"
 ::event/sequence 42
 ::thread/id "thr_123"
 ::turn/id "turn_456"
 ::item/id "item_789"
 ::event/data {:delta "Hello"}
 :wire/method "item/agentMessage/delta"}
```

The sequence is a local receipt counter. It is not a server replay cursor. Raw messages remain accessible through the low-level listener.

```clojure
(event/listen! conn
  {:thread-id "thr_123" :types #{:item/text-delta}}
  (fn [event] (print (event/text-delta event))))
```

Local event subscription does not resume a thread or create a server subscription. `thread/unsubscribe!` and `event/unlisten!` perform different operations.

The reader never calls user handlers directly. A per-listener serial queue preserves order. Independent workers handle callbacks and server requests, so a slow callback cannot prevent response correlation.

Queue limits are explicit. An overflow closes the affected observer with a structured error rather than silently dropping data. Internal workflow observation treats overflow as incomplete state. Request dispatch has its own bounded capacity. When that capacity is exhausted, dispatch returns a protocol error.

`event/apply-event` is a pure reducer over SDK state. It tracks threads, turns, items, pending interactions, plans, diffs, and usage. Callers can use it with `reduce`, `reductions`, atoms, or their own store.

Append ordered text deltas to provisional content. Replace that content with authoritative item-completion snapshots. Item projections must survive turn-completion payloads with absent or empty item collections.

Known lifecycle events update the projection. Unknown events remain observable. Replayed or duplicated lifecycle snapshots do not duplicate items. Text deltas have no assumed global deduplication key.

A pending interaction includes request kind, original RPC ID, context references, allowed response schema, and a connection-scoped reply token. The token includes a local generation to reject stale replies.

```clojure
(interaction/pending conn)
(interaction/respond! conn request {:decision :accept})
```

The response schema depends on the request kind. Permission grants, command decisions, elicitation content, tool results, refreshed tokens, and attestation tokens remain distinct values. A universal boolean approval would lose required information.

Only one handler owns a request. Observers can still inspect it. A deferred request remains available to REPL inspection until answered or resolved remotely.

An explicit handler can return a response map or a documented defer sentinel. A defer path has a bounded policy timeout. Default unattended behavior declines supported approval requests and returns an appropriate error for unsupported calls.

`serverRequest/resolved` invalidates the token. Local reply submission guarantees at most one send from this client. It cannot guarantee remote exactly-once processing after a connection failure.

Dynamic tool definitions are data sent to the server. Their Clojure implementations stay in a separate local handler registry. Resuming a thread with persisted tools still requires local implementations.

## REPL workflow

The following sketch shows the intended ordinary experience:

```clojure
(require '[codex.app-server :as server]
         '[codex.api :as api]
         '[codex.thread :as thread]
         '[codex.turn :as turn]
         '[codex.input :as input]
         '[codex.item :as item]
         '[codex.run :as run]
         '[codex.repl :as repl])

(def conn
  (server/connect!
    {:transport {:type :stdio :command ["codex" "app-server"]}
     :client-info {:name "clojure-repl" :version "0.1.0"}}))

(def context (thread/start! conn {:cwd "/workspace/project"}))
(def thread-id (get-in context [::thread/thread ::thread/id]))
(def watch (repl/watch! conn {:thread-id thread-id}))

(def work
  (run/start! conn thread-id
    {:input [(input/text "Explain the structure of this repository.")]}))

(run/snapshot work)
(repl/pending conn)
(def result (run/await! work 60000 ::still-running))

;; After completion, inspect all recorded items or derive a text view.
(when (map? result)
  (item/messages (::turn/items result)))

(repl/unwatch! watch)
(run/close! work)
(server/close! conn)
```

`run/start!` installs observation before submitting input. It buffers relevant events until the response identifies the turn. It then correlates by thread and turn IDs. This closes the race where a short turn finishes before the start response reaches the caller.

Tracked workflows require their lifecycle notifications. A connection that suppresses those notifications cannot start a tracked run. An intermediate error notification does not finish a run. The terminal turn event establishes completion.

The initial implementation limits tracked runs to ordinary user-input turns. Queued standalone tool output has a different lifecycle and remains available through `turn/start!` and catalog operations.

`run/await!` returns the completed projection, including status, items, and error data. A failed turn remains useful domain data. Local transport failures throw exceptions.

A local wait timeout returns the supplied sentinel without interrupting the turn. `run/interrupt!` requests interruption explicitly. `run/close!` releases local observation without claiming remote cancellation.

The projection is a client-observed view. It is not a replacement for persisted history. Missing observations are marked incomplete.

REPL helpers use bounded event history and captured output streams. Loading a namespace never connects, starts a process, changes a global var, or prints events.

There is no required global `*client*` or implicit current thread. REPL users can keep connections in their own vars and use several servers concurrently.

## Failure model

Use `ex-info` for request, transport, timeout, schema, and local lifecycle failures. Exception data includes category, operation, method, request ID, and relevant entity references.

```clojure
{:codex.error/category :rpc
 :op :turn/steer
 :wire/method "turn/steer"
 :rpc/code -32602
 :rpc/message "..."
 :rpc/data {...}}
```

Transport diagnostics redact credentials. Full request bodies are not included automatically.

Distinguish RPC rejection from turn failure. A successful `turn/start` RPC can lead to a failed turn later. A command exit code is command result data.

Request timeout means the reply did not arrive by the deadline. It does not establish whether the server performed the operation. Late responses become diagnostic events after pending state expires.

`abandon!` releases local pending state. It never promises wire cancellation. Turn interruption, command termination, and process termination retain their explicit domain operations.

Automatic retry is disabled unless a descriptor and caller policy allow it. Explicit overload rejection can use bounded exponential backoff with jitter. Ambiguous write failures never trigger automatic replay.

## Clojure and Babashka runtime plan

Publish one source library for both runtimes. Prefer ordinary `.clj` namespaces and shared data transformations. ClojureScript support is outside this design.

Candidate implementation dependencies:

- `cheshire.core` for JSON, with string keys at the wire boundary.
- `babashka.process` for owned subprocesses and pipes.
- `babashka.http-client.websocket` for TCP WebSocket connections.
- Core atoms, promises, and bounded worker queues for concurrency.

The WebSocket API is documented in [babashka/http-client](https://github.com/babashka/http-client/blob/main/API.md#babashkahttp-clientwebsocket). Babashka exposes a curated runtime, so compatibility requires execution under both runtimes. See the [Babashka book](https://book.babashka.org/).

Promise wrappers expose a shared deref contract or the explicit `await!` functions. Keep Java completion stages private. Do not require callers to use core.async, Manifold, or a particular application lifecycle library.

An optional core.async adapter can route events into caller-owned channels. It must preserve the same overflow and cleanup semantics.

The first supported transports are stdio and `ws`/`wss`. Unix-domain WebSocket support needs a separate adapter and compatibility work. Do not treat a Unix socket as raw JSONL or claim parity without tests. Callers can use a localhost TCP bridge meanwhile.

The initial compatibility matrix must cover:

- JVM Clojure and a pinned minimum Babashka version.
- Stdio and WebSocket handshake, framing, shutdown, and authentication headers.
- Server requests during active calls, slow handlers, and queue overflow.
- Completion-before-response races and interleaved threads.
- Nil versus omitted fields, unknown variants, arbitrary JSON keys, and partial history.
- Transport loss, expired replies, local wait timeout, and explicit interruption.

No runtime compatibility claim is established by this design document. Dependency versions and minimum runtimes remain implementation decisions after these tests.

## Implementation order

1. Record schema provenance and build an inspectable operation/schema catalog.
2. Implement stdio and WebSocket connections with bidirectional request dispatch.
3. Add schema-aware codecs, structured failures, and deterministic fake-transport tests.
4. Implement thread, turn, input, item, event, interaction, and run namespaces.
5. Prove the complete REPL workflow on both runtimes, including an approval round trip.
6. Add reducible pagination and the remaining domain conveniences from measured usage.

The first useful milestone is a client that connects, starts or resumes a thread, streams a turn, answers a server request, and returns inspectable results.

The central design commitment is explicit effects over plain data. Connections own communication. Values describe the domain. The operation catalog provides coverage. Curated functions and reducers make that coverage pleasant to use.
