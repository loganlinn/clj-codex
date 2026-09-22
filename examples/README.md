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
| [review_agent_json.clj](review_agent_json.clj) | Raw review events, JSON validation, original finding and verdict fields | Creates a thread and runs the reviewer with experimental raw events |
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

## Structured review output

Run the built-in reviewer and write its original JSON result:

```sh
bb examples/review_agent_json.clj /path/to/repo > review.json
```

The default target includes staged, unstaged, and untracked changes.
Use `--prompt 'Review changes against main.'` for a custom target.
The result keeps `findings`, confidence scores, and the overall verdict under
their original JSON keys. It requires no parsing of the rendered review report.

This example enables both `experimentalApi` and `experimentalRawEvents`.
It reads assistant messages from `rawResponseItem/completed`, validates the
review JSON, and waits for the matching `turn/completed` event.
A trailing Codex memory-citation block is omitted before JSON validation.
Extra prose and multiple JSON values still fail extraction.
A valid result with an empty `findings` array succeeds. Missing or invalid JSON
fails with a nonzero exit status. Valid results with findings also exit zero.

The raw stream is internal/experimental. The example follows the review rubric
and event flow in Codex revision `78245b47af`. Server changes can break extraction.
See the [app-server review reference](https://learn.chatgpt.com/docs/app-server#review)
for the built-in reviewer and its targets.
