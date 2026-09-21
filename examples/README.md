# Babashka examples

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

Install Babashka 1.12.218 or later.
Put `bb` and `codex` on `PATH`. No extra dependencies are required.

Run an example from the SDK checkout:

```sh
bb examples/catalog.clj --help
```

The scripts resolve the SDK classpath relative to their own files.
You can also invoke a script by absolute path from another directory.
Every runnable script supports `--help`.
See each example's namespace docstring for usage and behavior.
