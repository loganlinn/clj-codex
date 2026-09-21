# Working on clj-codex

Maintain one Clojure SDK for Codex app-server that runs on JVM Clojure and
Babashka. Preserve the central design: explicit effects over plain data.
Connections own communication, immutable values describe the domain, and an
inspectable operation catalog provides protocol coverage.

## Find the relevant contract

- For public API changes, read the affected namespaces in [src/codex](src/codex)
  and their entries in [API.md](API.md). Use [README.md](README.md) and
  [examples/README.md](examples/README.md) for usage and REPL workflows.
- For protocol changes, inspect the checked-in [schemas](apis/codex/app-server),
  [catalog](resources/codex/catalog.edn), and hand-authored
  [operation semantics](resources/codex/operation-semantics.edn).
- For dependency and runtime changes, check [deps.edn](deps.edn),
  [bb.edn](bb.edn), and the tested matrix in [CI](.github/workflows/ci.yml).
- Before changing the private Unix WebSocket backend, read its
  [provenance](src/codex/impl/websocket/PROVENANCE.md).

## Architecture and public API

- Keep transport, initialization, raw messages, request correlation, and
  connection lifecycle in `codex.app-server`. It must work without the domain API.
- Keep operation discovery, invocation, conversion, and pagination in
  `codex.api`; schema inspection and validation in `codex.schema`; pure event
  projection in `codex.event`; server-request ownership in `codex.interaction`;
  tracked turns in `codex.run`; and bounded observation in `codex.repl`.
- Keep framing, codecs, executors, generated indexes, pending-request tables,
  and transport adapters under `codex.impl.*`. Expose a transport extension API
  only when a concrete extension use case requires it.
- Use immutable maps for entity snapshots, references, tagged values, operations,
  and events. Reserve opaque handles and behavioral protocols or records for
  lifecycle resources and behavioral boundaries. Keep handle representations
  private; print IDs and status without credentials or full transcripts.
- Model threads, turns, and items as distinct entities. Keep input values separate
  from recorded items, and thread identity separate from session identity, fork
  ancestry, and spawned-agent ancestry. Snapshots carry no connection or I/O.
- Add curated domain functions for useful semantics and demonstrated workflows.
  Use the catalog for broader coverage, including attachments and sections, until
  usage justifies dedicated APIs. Avoid a universal entity/CRUD abstraction or a
  generated function for every endpoint.
- End external I/O function names in `!`, including reads. Pure constructors and
  queries have no suffix. Take the connection first, identity positionally, and
  optional arguments in a trailing map.
- Accept an ID or explicit reference when identity is unambiguous. Turn references
  contain both `:codex.thread/id` and `:codex.turn/id`. Encode the supplied turn ID
  as `expectedTurnId` in `turn/steer!`; never substitute a discovered active turn.
- Keep constructors usable as ordinary maps callers can build themselves. Use
  `api/submit!` and `api/await!` for concurrency instead of adding asynchronous,
  callback, and channel variants to every domain function.

## Operations and protocol coverage

- Separate schema-snapshot facts, curated SDK semantics, and availability on the
  connected server. Schema presence alone does not prove runtime support.
- Record the generator version and schema digest. Generate wire descriptors and
  schema indexes; hand-author semantic annotations, public names, and workflows.
  Infer neither idempotency nor stability from method names.
- Keep invocations as inspectable data, such as
  `{:op :thread/start :args {:cwd "/workspace/project"}}`, without connections,
  callbacks, promises, or generated classes. Keep descriptors separate.
- Expose schemas, effects, completion semantics, retry policy, availability,
  documentation, and experimental/deprecation metadata through the catalog.
  Preserve experimental and under-development labels in discovery results.
- Have domain functions assemble operation maps and use the catalog's codecs and
  return contracts, adding only documented domain semantics.
- Preserve `app-server/request!` as an explicit escape hatch for literal wire
  methods and string-keyed JSON, bypassing domain conversion.

## Values and lossless conversion

- Use namespace-qualified keys for domain entities and references. Use
  unqualified kebab-case keys for operation arguments, and `:type` with known
  keyword tags for small tagged values.
- Keep IDs, model/tool/provider names, paths, cursors, and user text as strings.
  Convert only known protocol enums to keywords.
- Make conversion schema-aware with explicit mappings. Preserve user-owned JSON
  keys in tool arguments/results, output schemas, injected Responses items, and
  arbitrary configuration values as string-keyed maps. A recursive case-conversion
  walk cannot preserve these values.
- Treat missing keys as omitted fields and present `nil` as explicit JSON null,
  subject to the field schema. Preserve the distinction between unloaded and
  loaded-empty collections through field presence and history-view metadata.
- Retain unknown object fields in `:codex.api/extensions` under their original
  wire names, unknown union variants as tagged opaque values with raw payloads,
  and unknown enum values as strings.
- Validate known public arguments strictly. Provide an explicit string-keyed
  extension escape hatch that checks collisions with encoded known fields.
- Keep wire timestamp units in the schema contract; offer explicit conversion
  helpers. Interpret paths in the relevant server or execution environment,
  without resolving remote paths against the client's working directory.
- Make `input/normalize` return a vector from a string, one input map, or an ordered
  collection of input maps. Preserve order and reject arbitrary objects instead
  of stringifying them. Keep standalone tool output separate from user text.
- Expose schemas for entities, arguments, results, events, and server-request
  replies through `codex.schema`. Build on the checked-in JSON Schema and explicit
  mappings; keep optional schema adapters optional and runtime operation
  independent of JVM-only schema generation.

## Results and completion

- Ordinary domain functions wait for the RPC response, not asynchronous work
  completion. Explicit workflow starters return handles under their documented
  contracts; workflow `await!` functions establish completion separately.
- Preserve thread context from `thread/start!`, `resume!`, and `fork!`, including
  thread, configuration, and instruction sources. Return a thread snapshot from
  `thread/read!` and an initial turn snapshot from `turn/start!`. Preserve both
  review thread reference and initial turn from `review/start!`.
- Return useful acknowledgement/status data, often `{}`, instead of replacing it
  with `nil` or a boolean. `history/compact!` acknowledges the request; it does not
  establish that compaction finished. Keep deprecated operations available through
  catalog entries with deprecation metadata.
- List operations return pages with `:data`, `:next-cursor`, and
  `:backwards-cursor` where supported. Implement `api/pages` and `api/entries` as
  `IReduceInit` reducibles: construction performs no I/O, reduction fetches pages,
  and `reduced` stops before another request. REPL printing must not fetch pages.

## Connections and concurrency

- Start the reader and request dispatcher before initialization, using the initial
  handler registry supplied at connection creation. Wait for the initialization
  response, send `initialized`, and return a ready connection. Close owned
  resources when initialization fails.
- Frame stdio as JSONL and WebSockets as JSON messages. Drain subprocess stderr
  independently from protocol stdout. Serialize writes, register pending state
  before sending bytes, and support concurrent pending calls.
- Correlate client responses by ID while preserving server-request IDs in a
  separate request path. `request!` returns a pending handle; `await!` returns its
  wire result or throws. `notify!` has no response; `reply!` sends a result or
  protocol error for a server-request token.
- Bound queues and reject pending calls on transport closure. Make `close!`
  idempotent and expose diagnostic connection state as data.
- A connection owns subprocesses it starts. Closing a remote connection leaves
  the remote server running; closing any connection does not imply turn
  interruption. Keep transport authentication separate from account login.
- Require a fresh handshake and explicit thread resumption after connection loss.
  Do not transparently reconnect or replay; remote work may still be running.
- Run user handlers off the reader. Preserve order with a serial queue per
  listener and independent workers for callbacks and server requests, so slow
  handlers cannot block response correlation.
- On observer overflow, close the affected observer with a structured error and
  mark affected workflow state incomplete. Give request dispatch its own bounded
  capacity and return a protocol error when that capacity is exhausted.

## Events and interactions

- Preserve event type, connection ID, local receipt sequence, entity references,
  typed payload, and wire method in normalized envelopes. The sequence is a local
  counter, not a replay cursor. Keep raw messages available to low-level listeners.
- Keep local observation separate from server subscription. `event/listen!` does
  not resume a thread; `event/unlisten!` and `thread/unsubscribe!` have different
  effects.
- Keep `event/apply-event` pure. Append ordered deltas to provisional content, then
  replace it with authoritative item-completion snapshots. Preserve projected
  items when turn completion contains absent or empty item collections.
- Apply known lifecycle events, retain unknown events for observation, and avoid
  duplicate items from repeated lifecycle snapshots. Assume no global
  deduplication key for text deltas.
- Allow many event observers but one selected responder per server request.
  Pending interactions retain request kind, original RPC ID, context references,
  allowed response schema, and a connection-scoped reply token with a local
  generation that rejects stale replies.
- Preserve request-specific reply schemas for permission grants, command
  decisions, elicitation, tool results, token refresh, and attestation. A boolean
  approval cannot represent all of them.
- Let handlers return a response map or documented defer sentinel. Keep deferred
  requests inspectable until answered or remotely resolved, with a bounded policy
  timeout. Default unattended behavior declines supported approvals and returns
  an appropriate error for unsupported requests.
- Invalidate reply tokens on `serverRequest/resolved`. Guarantee at most one local
  send, without claiming remote exactly-once processing after connection failure.
- Keep dynamic tool definitions as server-bound data and their Clojure
  implementations in a separate local handler registry. Resumed threads still
  need local implementations for persisted tools.

## Tracked workflows, execution, and the REPL

- Install run observation before submitting input. Buffer relevant events until
  the start response supplies the turn ID, then correlate by both thread and turn
  IDs. Cover completion-before-response races.
- Reject tracked workflows when their required lifecycle notifications are
  suppressed. Finish a run on its terminal turn event, not an intermediate error
  notification. Track ordinary user-input turns; use `turn/start!` or the catalog
  for queued standalone tool output.
- Return the completed projection, including status, items, and error data, from
  `run/await!`. Treat failed turns as domain data and local transport failures as
  exceptions. Mark missing observations incomplete; projections are observed
  views rather than persisted history.
- A local wait timeout returns its sentinel while leaving pending work intact.
  Keep interruption explicit. `run/close!` releases local observation without
  cancelling remote work.
- Keep sandboxed `codex.command`, experimental unsandboxed `codex.process`, and
  `thread/shellCommand` distinct. Never fall back from sandboxed execution to
  `thread/shellCommand`.
- `command/exec!` waits for buffered results. `command/start!` immediately returns
  a streaming handle with a client-assigned process ID while its RPC stays pending
  until execution finishes. Support output, stdin, resize, and termination
  concurrently through that handle.
- Track process acknowledgement separately from its later exit notification.
  Preserve bytes across output chunks in both execution APIs; expose text through
  explicit incremental UTF-8 decoding.
- Bound REPL event history and capture output streams. Namespace loading must not
  connect, start processes, mutate global state, or print events. Keep connections
  and thread selection explicit so callers can use several servers concurrently.

## Failure semantics

- Use `ex-info` for request, transport, timeout, schema, and local lifecycle
  failures. Include category, operation, wire method, request ID, and relevant
  entity references in exception data. Redact credentials and omit full request
  bodies from automatic diagnostics.
- Distinguish RPC rejection, later turn failure, and command exit status. A
  successful start RPC can lead to a failed turn; exit codes remain result data.
- A request timeout establishes only that no reply arrived by the deadline.
  Surface late responses as diagnostic events after pending state expires.
  `abandon!` releases local pending state without promising wire cancellation.
- Disable automatic retries unless both descriptor and caller policy allow them.
  Explicit overload rejection may use bounded exponential backoff with jitter;
  ambiguous write failures must not cause automatic replay.

## Runtime compatibility and validation

- Prefer shared `.clj` source and data transformations for JVM Clojure and Babashka.
  ClojureScript is outside the current scope. Keep Java completion stages private
  and expose deref or `await!`; callers must not need a particular concurrency or
  lifecycle library. Optional adapters must preserve overflow and cleanup behavior.
- Support stdio, TCP `ws`/`wss`, and `ws` over filesystem Unix sockets. Use upstream
  `babashka.http-client.websocket` for TCP and the private
  `codex.impl.websocket.*` backend when `:unix-socket` is supplied. The Unix backend
  uses HTTP Upgrade and WebSocket framing and loads lazily, requiring Java 16+
  with Unix socket support or a compatible Babashka runtime.
- For runtime changes, run the shared suite in both runtimes with `bb test` and
  `bb test:jvm`. Run `bb fmt:check` for Clojure changes. Treat compatibility claims
  as established only by execution on the relevant runtime and transport.
- Test changed contracts with deterministic fixtures: handshake, framing,
  shutdown, authentication headers, concurrent requests, slow handlers, overflow,
  completion races, interleaved threads, lossless conversion, partial history,
  transport loss, stale replies, wait timeouts, and explicit interruption as
  applicable. Use `bb test:unix-live` for an opt-in live Unix socket smoke test.
- For schema/catalog changes, use the generator version pinned in
  [generator-version.txt](resources/codex/generator-version.txt) with `bb codegen`.
  Verify with `bb schemas:check` and `bb codegen:check`; run `bb test:codegen` when
  changing generation logic. Keep generated outputs and provenance consistent.
- Update public docstrings and regenerate [API.md](API.md) with `bb quickdoc` when
  changing documented public contracts. Keep relevant examples consistent.
