# Babashka examples

Start with [the REPL tour](repl.clj) to explore returned values one form at a time.
Use the scripts for repeatable queries and complete workflows.

## Index

| Example | Topics | Server effects |
| --- | --- | --- |
| [repl.clj](repl.clj) | Connections, state, history, schemas, raw RPC, turns, approvals, commands, cleanup | Only the forms you evaluate |
| [catalog.clj](catalog.clj) | Operation catalog, protocol provenance, argument and entity schemas | None. Uses bundled data |
| [state.clj](state.clj) | Account, limits, configuration, models, skills, MCP, loaded threads | Reads the selected topic |
| [unix_socket.clj](unix_socket.clj) | Unix WebSocket connection, initialization, loaded threads, cleanup | Reads an existing server; leaves it running |
| [threads.clj](threads.clj) | Stored threads, reducible pagination, turns and items | Reads stored history |
| [watch.clj](watch.clj) | Thread subscription, events, state reduction, pending requests | Resumes a thread and observes it |
| [core_async.clj](core_async.clj) | Persisted events, core.async mult/tap, independent consumers | Reads a local session without resuming it |
| [turn.clj](turn.clj) | Input values, tracked turns, completion, interruption | Creates a thread and runs the model |
| [review_agent.clj](review_agent.clj) | Git repository paths, review targets, events, final review report | Creates a thread and runs the reviewer |
| [support.clj](support.clj) | CLI parsing, classpath setup, connection options, error reporting | Shared helpers. Loading starts no server |

## Setup

Install Babashka 1.12.218 or later, and put `bb` and `codex` on `PATH`.
The examples use built-in `babashka.cli`, `babashka.fs`, and `babashka.process` libraries.
No extra dependencies are required.

Run these commands from the SDK checkout:

```sh
bb examples/catalog.clj --help
bb examples/catalog.clj --op thread/read
bb examples/catalog.clj --schema-name Thread
bb examples/state.clj models
bb examples/threads.clj --limit 10
```

The scripts resolve the SDK classpath relative to their own files.
You can also invoke a script by absolute path from another directory.
Every runnable script supports `--help`. Invalid arguments and runtime failures return a nonzero exit status.
Data queries print EDN. The review script prints its report to stdout and progress to stderr.

Model turns and reviews require a configured Codex provider or account and can consume paid usage.
The scripts use the existing Codex configuration. They do not start a login flow.

## Explore in the REPL

```sh
bb --classpath .:src:resources:apis repl
```

```clojure
(require 'examples.repl)
(in-ns 'examples.repl)
```

Evaluate individual forms inside [repl.clj](repl.clj).
Its `comment` block does not run on load.
Use `keys`, `get-in`, `select-keys`, `pprint`, and ordinary sequence functions to inspect results.
Finish with `(server/close! conn)`.

## Local and existing servers

By default, each connected script starts its own `codex app-server` subprocess over stdio.
It closes that subprocess in `finally`.
Stored history can be shared through the same Codex home, but loaded threads belong to the connected server process.
A fresh stdio server does not expose another process's live state.

Start a TCP WebSocket server in another terminal:

```sh
codex app-server --listen ws://127.0.0.1:4500
```

Connect the exploration scripts to that server:

```sh
bb examples/state.clj loaded --url ws://127.0.0.1:4500
bb examples/threads.clj --url ws://127.0.0.1:4500 --limit 20
bb examples/watch.clj --url ws://127.0.0.1:4500 --id THREAD_ID --timeout-ms 60000
```

These scripts close their connection without stopping the existing server.
`watch.clj` resumes the selected thread to establish a remote subscription.
Its final projection contains events observed during that invocation, not the complete stored history.
An idle thread can produce no events.

To observe a local session from another terminal, use [core_async.clj](core_async.clj).
It reads the session log, which different server processes can share.

For an authenticated WebSocket endpoint, pass `--token-env MY_CODEX_TRANSPORT_TOKEN` with `--url`.
The script reads the credential from that environment variable.
Use `--experimental` to opt into experimental operations.
For a Unix-domain WebSocket connection, start a server in another terminal:

```sh
codex app-server --listen unix:///tmp/codex.sock
```

Run [the Unix socket example](unix_socket.clj) to inspect that server:

```sh
bb examples/unix_socket.clj /tmp/codex.sock
bb examples/unix_socket.clj --help
```

The example reads the connection metadata and one page of loaded thread IDs.
It closes its client connection and leaves the server running.
Unix transport requires Java 16+ with OS Unix socket support, or a compatible Babashka runtime such as 1.12.218.

In the REPL, pass both `:url` and `:unix-socket`:

```clojure
(def conn
  (server/connect!
    {:transport {:type :websocket
                 :url "ws://localhost/"
                 :unix-socket "/tmp/codex.sock"}}))
```

The other exploration scripts currently expose TCP URLs through `--url`.
See [development tasks](../README.md#development) for the opt-in live Unix socket smoke test.

## Inspect current state and history

```sh
bb examples/state.clj connection
bb examples/state.clj account
bb examples/state.clj limits
bb examples/state.clj config --cwd /absolute/path/to/project
bb examples/state.clj skills --cwd /absolute/path/to/project
bb examples/state.clj mcp
bb examples/threads.clj --id THREAD_ID
```

Available topics depend on the server version and configured provider.
For example, account rate limits require a supported account.
Models, MCP status, and loaded threads return one page. `threads.clj` shows how to reduce across pages.
Configuration output can contain local paths and provider settings.

## Observe a local session

```sh
# Replay recorded events, then follow new events until Ctrl-C.
bb examples/core_async.clj SESSION_ID

# Print only new events.
bb examples/core_async.clj SESSION_ID --from-now

# Read a stopped session and exit.
bb examples/core_async.clj SESSION_ID --once

# Emit every complete log record as JSONL.
bb examples/core_async.clj SESSION_ID --once --all --format jsonl
```

Use the thread ID printed by `review_agent.clj` or returned by `thread/start!` as `SESSION_ID`.
The example uses `thread/read!` to locate the local log, then closes its lookup server.
It reads the log without resuming the session or starting a model turn.
The lookup uses the current Codex home, including `CODEX_HOME` when set.

By default, the example prints `event_msg` records as EDN, including their timestamps and payloads.
Raw JSON keys remain strings.
The `--all` option also includes metadata, context, and response records.
These are persisted log records. They differ from the domain events returned by `codex.event/listen!`.
The log can omit live token deltas, and events appear only after Codex writes them to disk.
The log path and record format are internal Codex details that can change between versions.

The example polls every 250 ms and uses fixed channel buffers of 32 records.
It waits for a complete line before decoding UTF-8 or JSON.
The `--once` option stops at the initial file size and omits an incomplete final record.
The `--from-now` option skips existing records, including a record already in progress at attachment.
Following continues across turn completion, so an idle or stopped session waits until Ctrl-C.
Ephemeral sessions have no persisted log to observe.

### Fan out events with core.async

The example feeds a channel into `core.async/mult` and attaches two consumers with `core.async/tap`.
One consumer prints records. The other counts records with `core.async/reduce` and reports the total to stderr after replay finishes.
Both taps receive every selected record, in order.
Babashka includes core.async, so this example needs no additional dependencies.

Both taps attach before the producer starts because a mult does not replay earlier values.
A full tap buffer pauses delivery until its consumer catches up.
The producer and printer use `async/thread` for blocking file and output operations.
The producer closes its channel on completion or error. The mult then closes both taps after delivery.
The example waits for both consumers and propagates worker errors to the CLI.

Load the example in a Babashka REPL to attach your own consumers:

```clojure
(load-file "examples/core_async.clj")
(def path (session-path! "SESSION_ID"))
(def events (async/chan 32))
(def broadcast (async/mult events))
(def latest (async/tap broadcast (async/chan (async/sliding-buffer 1))))
(def counted (async/tap broadcast (async/chan 32)))
(def total (async/reduce (fn [n _] (inc n)) 0 counted))
(def reader
  (async/thread
    (try
      (stream-log! path events {:once true})
      {:ok true}
      (catch Exception e {:error e}))))

(async/<!! reader)  ; Completion or a read error.
(async/<!! total)   ; Number of records delivered to the counter.
(async/<!! latest)  ; Last record retained by the sliding buffer.
```

The sliding buffer retains only the latest record, while the counter receives every record.
For continuous observation, pass a channel as `:stop` and omit `:once` in `stream-log!`.
Close that channel to stop the producer, including a put blocked by a slow tap.
Consumers must drain their channels so the mult can finish delivery and close the taps.
Use `async/untap` to detach a consumer, then close and drain its channel to release any delivery already in progress.
See the [core.async mult and tap reference](https://clojure.github.io/core.async/clojure.core.async.html#var-mult).

## Run a turn

```sh
bb examples/turn.clj --cwd /path/to/repo --prompt 'Explain the test structure.'
```

This example creates a read-only thread with approval policy `never`.
It waits for completion and prints message items.
Its timeout requests interruption before cleanup. The SDK's `run/await!` alone does not interrupt work.

## Run the review agent

```sh
# Review uncommitted changes in the current Git working tree.
bb examples/review_agent.clj

# Print parsed findings and the original report as EDN.
bb examples/review_agent.clj --format edn

# Review a different repository, including a path with spaces.
bb examples/review_agent.clj '/path/to/my repo'

# Supply a custom review target through free-form instructions.
bb examples/review_agent.clj /path/to/repo \
  --prompt 'Review changes against main. Focus on concurrency bugs.' \
  --timeout-ms 600000

# From another repository, use the absolute path to the example.
bb /path/to/clj-codex/examples/review_agent.clj .
```

The repository defaults to the caller's current directory.
`git rev-parse` resolves the working tree root, including linked worktrees and paths inside a repository.
The script starts its server in that root and creates a read-only thread with approval policy `never`.

Without `--prompt`, the target includes staged, unstaged, and untracked changes.
With `--prompt`, custom instructions replace that default target. Include the desired comparison in the prompt.
`--model MODEL_ID` overrides the configured model.

The example uses `codex.review/start!` and the built-in Codex reviewer.
It does not require a separately installed `review-agent` skill.
It installs its event listener before submission and waits for the terminal turn event.
The final report comes from the `exitedReviewMode` item.

`review!` returns the result of `codex.review/parse-report` as a Clojure map.
The CLI prints the map as EDN by default. `--format text` prints the original report.
You can also parse a report without a server:

```clojure
(require '[codex.review :as review])
(review/parse-report report)
;; => {:findings [{:title "[P1] Preserve queued work"
;;                 :priority 1
;;                 :body "Closing here discards queued requests."
;;                 :code-location {:absolute-file-path "/repo/queue.clj"
;;                                 :line-range {:start 12 :end 14}}}]
;;     :report-text "...original report..."}
```

The parser recognizes Codex's `Review comment:` and `Full review comments:` blocks.
It preserves Markdown bodies and reads priority from a `[P0]` through `[P3]` title prefix.
The rendered report omits confidence scores and the overall correctness verdict. The parser cannot recover those fields.
The parser extracts findings only from a complete, recognized findings block. It does not validate the findings against the repository.
Prose-only reports and malformed blocks both return an empty findings vector.
Empty findings means none were extracted, not that the review passed. `:report-text` always contains the original report.
This parser depends on Codex's text format. It is not a structured protocol guarantee.

A completed review returns exit status zero, even when it reports findings.
A failed turn, missing report, or timeout returns a nonzero status.
On timeout, the script requests interruption before it closes the owned server.

## References

- [Codex app-server API and review workflow](https://learn.chatgpt.com/docs/app-server)
- [Babashka CLI argument parsing](https://github.com/babashka/cli)
- [SDK overview](../README.md)
