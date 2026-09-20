# Table of contents
-  [`codex.account`](#codex.account)  - Codex account state.
    -  [`cancel-login!`](#codex.account/cancel-login!) - Cancel a managed login by ID.
    -  [`limits!`](#codex.account/limits!) - Read ChatGPT rate limits.
    -  [`login!`](#codex.account/login!) - Start a login ceremony.
    -  [`logout!`](#codex.account/logout!) - Sign out of the Codex account.
    -  [`read!`](#codex.account/read!) - Read account state and whether provider authentication is needed.
    -  [`usage!`](#codex.account/usage!) - Read account token usage.
-  [`codex.api`](#codex.api)  - Inspectable operations, schema-aware invocation, and reducible pagination.
    -  [`await!`](#codex.api/await!) - Await a submitted operation.
    -  [`describe`](#codex.api/describe) - Describe an operation keyword such as :thread/read or :thread.goal/set.
    -  [`entries`](#codex.api/entries) - Return a reducible of entries across pages.
    -  [`invoke!`](#codex.api/invoke!) - Run an operation and return its domain result after the RPC response.
    -  [`operations`](#codex.api/operations) - Return the operation catalog, optionally filtered by a descriptor predicate.
    -  [`pages`](#codex.api/pages) - Return a reducible of pages.
    -  [`provenance`](#codex.api/provenance) - Return the Codex generator version and schema digest.
    -  [`submit!`](#codex.api/submit!) - Submit {:op keyword :args map} and return a pending operation.
-  [`codex.app`](#codex.app)  - Connector metadata and effective runtime state.
    -  [`installed!`](#codex.app/installed!) - Read enabled/callable app runtime state.
    -  [`list!`](#codex.app/list!) - Fetch a page of available apps.
    -  [`read!`](#codex.app/read!) - Read app metadata, preserving missing IDs.
-  [`codex.app-server`](#codex.app-server)  - Bidirectional app-server connections.
    -  [`abandon!`](#codex.app-server/abandon!) - Release local pending state.
    -  [`await!`](#codex.app-server/await!) - Await a pending RPC.
    -  [`close!`](#codex.app-server/close!) - Close the connection and owned process.
    -  [`connect!`](#codex.app-server/connect!) - Connect over :stdio or :websocket and complete initialize/initialized.
    -  [`info`](#codex.app-server/info) - Return initialization results, capabilities, and safe connection metadata.
    -  [`listen!`](#codex.app-server/listen!) - Observe raw incoming envelopes on an ordered bounded worker queue.
    -  [`notify!`](#codex.app-server/notify!) - Send a raw notification.
    -  [`pending-requests`](#codex.app-server/pending-requests) - Return pending server requests, including their reply tokens.
    -  [`reply!`](#codex.app-server/reply!) - Reply once to a server request token.
    -  [`request!`](#codex.app-server/request!) - Submit a raw RPC and return a pending handle.
    -  [`status`](#codex.app-server/status) - Return the connection lifecycle state.
    -  [`unlisten!`](#codex.app-server/unlisten!) - Stop a local raw observer.
-  [`codex.command`](#codex.command)  - Sandboxed command execution.
    -  [`await!`](#codex.command/await!) - Await exit and collected byte output.
    -  [`exec!`](#codex.command/exec!) - Run a buffered command and return stdout/stderr strings and exit status.
    -  [`output`](#codex.command/output) - Snapshot collected stdout/stderr bytes and truncation status.
    -  [`resize!`](#codex.command/resize!) - Resize a PTY with {:rows n :cols n}.
    -  [`start!`](#codex.command/start!) - Start a streaming command.
    -  [`terminate!`](#codex.command/terminate!) - Request command termination.
    -  [`write!`](#codex.command/write!) - Write stdin bytes; nil bytes with close? closes stdin.
-  [`codex.config`](#codex.config)  - Effective configuration and persistent edits.
    -  [`patch!`](#codex.config/patch!) - Apply a vector of configuration edits atomically.
    -  [`read!`](#codex.config/read!) - Read effective configuration, optionally with layers.
    -  [`requirements!`](#codex.config/requirements!) - Read managed requirements.
    -  [`write!`](#codex.config/write!) - Write a configuration value at a wire key path.
-  [`codex.event`](#codex.event)  - Domain notifications and a pure projection of observed conversation state.
    -  [`apply-event`](#codex.event/apply-event) - Pure reducer.
    -  [`listen!`](#codex.event/listen!) - Observe matching domain events.
    -  [`matches?`](#codex.event/matches?) - Match an event against optional :thread-id, :turn-id, :item-id, and :types.
    -  [`normalize`](#codex.event/normalize) - Convert a raw notification/request to a domain envelope.
    -  [`terminal?`](#codex.event/terminal?) - Is this a terminal turn notification?.
    -  [`text-delta`](#codex.event/text-delta) - Return an agent text delta, or nil.
    -  [`turn-snapshot`](#codex.event/turn-snapshot) - Read a turn projection, with items in observed order.
    -  [`unlisten!`](#codex.event/unlisten!) - Release an event observer.
-  [`codex.fs`](#codex.fs)  - Files on the server host.
    -  [`copy!`](#codex.fs/copy!) - Copy paths using explicit sourcePath/destinationPath equivalents.
    -  [`delete!`](#codex.fs/delete!) - Remove a server-side path.
    -  [`list!`](#codex.fs/list!) - Read a directory.
    -  [`mkdir!`](#codex.fs/mkdir!) - Create a directory.
    -  [`read!`](#codex.fs/read!) - Read a file as a byte array.
    -  [`stat!`](#codex.fs/stat!) - Read path metadata.
    -  [`unwatch!`](#codex.fs/unwatch!) - Remove a server-side watch by ID.
    -  [`watch!`](#codex.fs/watch!) - Create a server-side watch.
    -  [`write!`](#codex.fs/write!) - Write a byte array to a server-side file.
-  [`codex.goal`](#codex.goal)  - Goal operations and pure budget accounting.
    -  [`clear!`](#codex.goal/clear!) - Clear a thread's goal.
    -  [`read!`](#codex.goal/read!) - Read a thread's current goal.
    -  [`remaining-tokens`](#codex.goal/remaining-tokens) - Remaining token budget, or nil for an unbudgeted goal.
    -  [`set!`](#codex.goal/set!) - Set or update a goal.
-  [`codex.history`](#codex.history)  - Stored history queries and explicit context changes.
    -  [`compact!`](#codex.history/compact!) - Request compaction and return acknowledgement; progress arrives as events.
    -  [`inject!`](#codex.history/inject!) - Append string-keyed Responses API items without starting a turn.
    -  [`items!`](#codex.history/items!) - Fetch a page of persisted items (experimental).
    -  [`turns!`](#codex.history/turns!) - Fetch a page of persisted turns (experimental).
-  [`codex.input`](#codex.input)  - Pure constructors for user input.
    -  [`audio`](#codex.input/audio) - Audio URL input.
    -  [`image`](#codex.input/image) - Image URL input.
    -  [`local-audio`](#codex.input/local-audio) - Audio path on the server host.
    -  [`local-image`](#codex.input/local-image) - Image path on the server host.
    -  [`mention`](#codex.input/mention) - App or other mention reference.
    -  [`normalize`](#codex.input/normalize) - Normalize a string, input map, or ordered collection to a vector.
    -  [`skill`](#codex.input/skill) - Explicit skill reference.
    -  [`text`](#codex.input/text) - Text input.
-  [`codex.interaction`](#codex.interaction)  - Server-initiated requests with typed replies and explicit local responder ownership.
    -  [`handle!`](#codex.interaction/handle!) - Install the sole responder for a request kind.
    -  [`pending`](#codex.interaction/pending) - Return typed pending requests, including reply tokens.
    -  [`reject!`](#codex.interaction/reject!) - Send a JSON-RPC error for a pending request.
    -  [`resolved?`](#codex.interaction/resolved?) - Has this request been answered, expired, or cleared?.
    -  [`respond!`](#codex.interaction/respond!) - Send a typed response to a pending request exactly once locally.
    -  [`unhandle!`](#codex.interaction/unhandle!) - Remove a handler registration without replacing another owner.
-  [`codex.item`](#codex.item)  - Pure queries over immutable item values.
    -  [`changes`](#codex.item/changes) - Select file-change items.
    -  [`commands`](#codex.item/commands) - Select command execution items.
    -  [`completed?`](#codex.item/completed?) - Was an item completed, or does it carry a terminal execution status?.
    -  [`messages`](#codex.item/messages) - Select user and agent message items, preserving order.
    -  [`text`](#codex.item/text) - Return textual content for an item, or nil.
-  [`codex.mcp`](#codex.mcp)  - MCP integration through app-server, not a separate MCP client.
    -  [`call!`](#codex.mcp/call!) - Call a tool through a thread's configured MCP server.
    -  [`login!`](#codex.mcp/login!) - Start an MCP OAuth flow; completion arrives as an event.
    -  [`reload!`](#codex.mcp/reload!) - Reload MCP configuration from disk.
    -  [`resource!`](#codex.mcp/resource!) - Read a resource through an initialized MCP server.
    -  [`servers!`](#codex.mcp/servers!) - Fetch a page of MCP server status.
-  [`codex.model`](#codex.model)  - Discover models and provider bounds.
    -  [`capabilities!`](#codex.model/capabilities!) - Read provider capabilities for model/provider arguments.
    -  [`list!`](#codex.model/list!) - Fetch one model page.
-  [`codex.permission`](#codex.permission)  - Permission discovery and pure policy values.
    -  [`external-sandbox`](#codex.permission/external-sandbox) - Policy for an externally sandboxed execution host.
    -  [`grant`](#codex.permission/grant) - Permission reply value.
    -  [`profiles!`](#codex.permission/profiles!) - Fetch available permission profiles for a working directory.
    -  [`read-only`](#codex.permission/read-only) - Read-only sandbox policy with optional boolean network access.
    -  [`workspace-write`](#codex.permission/workspace-write) - Workspace-write policy for explicit server-side roots.
-  [`codex.plugin`](#codex.plugin)  - Plugin operations.
    -  [`install!`](#codex.plugin/install!) - Install a plugin by explicit source and name arguments.
    -  [`list!`](#codex.plugin/list!) - Discover plugins and marketplaces.
    -  [`read!`](#codex.plugin/read!) - Read a plugin by explicit source and name arguments.
    -  [`skill!`](#codex.plugin/skill!) - Read remote plugin skill content.
    -  [`uninstall!`](#codex.plugin/uninstall!) - Uninstall a plugin.
-  [`codex.process`](#codex.process)  - Explicit unsandboxed process control.
    -  [`await!`](#codex.process/await!) - Await process exit and collected bytes.
    -  [`kill!`](#codex.process/kill!) - Request process termination.
    -  [`output`](#codex.process/output) - Snapshot collected stdout/stderr bytes and truncation status.
    -  [`resize!`](#codex.process/resize!) - Resize a process PTY with {:rows n :cols n}.
    -  [`start!`](#codex.process/start!) - Start an unsandboxed process with explicit :command and :cwd.
    -  [`write!`](#codex.process/write!) - Write process stdin bytes.
-  [`codex.repl`](#codex.repl)  - Explicit, bounded REPL observation.
    -  [`explain`](#codex.repl/explain) - Print and return an operation descriptor.
    -  [`history`](#codex.repl/history) - Return the bounded event history of a watcher.
    -  [`inspect`](#codex.repl/inspect) - Print and return a value.
    -  [`pending`](#codex.repl/pending) - Inspect typed pending server requests.
    -  [`unwatch!`](#codex.repl/unwatch!) - Stop a REPL watcher.
    -  [`watch!`](#codex.repl/watch!) - Observe and print domain events.
-  [`codex.review`](#codex.review)  - Review targets, submission, and parsing of rendered reports.
    -  [`branch`](#codex.review/branch) - Review against a base branch.
    -  [`commit`](#codex.review/commit) - Review a commit.
    -  [`custom`](#codex.review/custom) - Review using custom instructions.
    -  [`parse-report`](#codex.review/parse-report) - Parse the built-in reviewer's rendered report into plain-keyed data.
    -  [`start!`](#codex.review/start!) - Start a review.
    -  [`uncommitted`](#codex.review/uncommitted) - Review staged, unstaged, and untracked changes.
-  [`codex.run`](#codex.run)  - Track a user-input turn through its terminal event.
    -  [`await!`](#codex.run/await!) - Return the terminal turn projection, including failed-turn data.
    -  [`close!`](#codex.run/close!) - Stop local observation without interrupting the remote turn.
    -  [`interrupt!`](#codex.run/interrupt!) - Request interruption of this run's identified turn.
    -  [`snapshot`](#codex.run/snapshot) - Return the current immutable turn projection.
    -  [`start!`](#codex.run/start!) - Start and track an ordinary user-input turn.
-  [`codex.schema`](#codex.schema)  - Inspect and check the bundled protocol schemas.
    -  [`describe`](#codex.schema/describe) - Return a JSON Schema document (string keys).
    -  [`explain`](#codex.schema/explain) - Return structural schema errors, or an empty vector.
    -  [`schemas`](#codex.schema/schemas) - Return descriptors for exported schemas and nested value definitions.
    -  [`valid?`](#codex.schema/valid?) - Does a wire JSON value satisfy the schema checks?.
-  [`codex.skill`](#codex.skill)  - Discover and configure standalone skills.
    -  [`disable!`](#codex.skill/disable!) - Disable a skill by server-side path.
    -  [`enable!`](#codex.skill/enable!) - Enable a skill by server-side path.
    -  [`extra-roots!`](#codex.skill/extra-roots!) - Replace process-level extra skill roots.
    -  [`list!`](#codex.skill/list!) - Discover skills, optionally for :cwds and with :force-reload.
-  [`codex.thread`](#codex.thread)  - Conversation lifecycle.
    -  [`archive!`](#codex.thread/archive!) - Archive a thread and attempt to archive spawned descendants.
    -  [`delete!`](#codex.thread/delete!) - Permanently delete a thread and spawned descendants.
    -  [`fork!`](#codex.thread/fork!) - Fork history, optionally through :last-turn-id.
    -  [`list!`](#codex.thread/list!) - Fetch one page of stored threads.
    -  [`loaded!`](#codex.thread/loaded!) - Fetch a page of loaded thread IDs.
    -  [`patch!`](#codex.thread/patch!) - Patch persisted metadata.
    -  [`read!`](#codex.thread/read!) - Read a stored snapshot without subscribing.
    -  [`ref`](#codex.thread/ref) - Extract an explicit thread reference from an ID or thread value.
    -  [`rename!`](#codex.thread/rename!) - Set a thread's display name.
    -  [`restore!`](#codex.thread/restore!) - Restore an archived thread.
    -  [`resume!`](#codex.thread/resume!) - Load and subscribe to a stored thread.
    -  [`start!`](#codex.thread/start!) - Create a thread and return its thread/configuration context.
    -  [`unsubscribe!`](#codex.thread/unsubscribe!) - Remove this connection's remote thread subscription.
-  [`codex.turn`](#codex.turn)  - Turn control.
    -  [`interrupt!`](#codex.turn/interrupt!) - Request interruption.
    -  [`ref`](#codex.turn/ref) - Construct or check a reference containing both thread and turn IDs.
    -  [`start!`](#codex.turn/start!) - Submit input and return the initial turn snapshot.
    -  [`steer!`](#codex.turn/steer!) - Append input only to the explicitly identified active turn.
    -  [`terminal?`](#codex.turn/terminal?) - Is a turn snapshot terminal?.

-----
# <a name="codex.account">codex.account</a>


Codex account state. Separate from transport authentication.




## <a name="codex.account/cancel-login!">`cancel-login!`</a>
``` clojure
(cancel-login! c login-id)
```
Function.

Cancel a managed login by ID.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/account.clj#L6-L7">Source</a></sub></p>

## <a name="codex.account/limits!">`limits!`</a>
``` clojure
(limits! c)
(limits! c opts)
```
Function.

Read ChatGPT rate limits.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/account.clj#L9-L9">Source</a></sub></p>

## <a name="codex.account/login!">`login!`</a>
``` clojure
(login! c args)
```
Function.

Start a login ceremony. Completion arrives through account events.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/account.clj#L4-L5">Source</a></sub></p>

## <a name="codex.account/logout!">`logout!`</a>
``` clojure
(logout! c)
```
Function.

Sign out of the Codex account.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/account.clj#L8-L8">Source</a></sub></p>

## <a name="codex.account/read!">`read!`</a>
``` clojure
(read! c)
(read! c opts)
```
Function.

Read account state and whether provider authentication is needed.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/account.clj#L2-L3">Source</a></sub></p>

## <a name="codex.account/usage!">`usage!`</a>
``` clojure
(usage! c)
(usage! c opts)
```
Function.

Read account token usage.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/account.clj#L10-L10">Source</a></sub></p>

-----
# <a name="codex.api">codex.api</a>


Inspectable operations, schema-aware invocation, and reducible pagination.




## <a name="codex.api/await!">`await!`</a>
``` clojure
(await! pending)
(await! pending timeout-ms timeout-value)
```
Function.

Await a submitted operation. A timed wait never abandons or interrupts remote work.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/api.clj#L68-L79">Source</a></sub></p>

## <a name="codex.api/describe">`describe`</a>
``` clojure
(describe op)
```
Function.

Describe an operation keyword such as :thread/read or :thread.goal/set.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/api.clj#L15-L16">Source</a></sub></p>

## <a name="codex.api/entries">`entries`</a>
``` clojure
(entries conn operation)
```
Function.

Return a reducible of entries across pages. Does not fetch during construction.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/api.clj#L102-L111">Source</a></sub></p>

## <a name="codex.api/invoke!">`invoke!`</a>
``` clojure
(invoke! conn operation)
(invoke! conn operation opts)
```
Function.

Run an operation and return its domain result after the RPC response.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/api.clj#L81-L83">Source</a></sub></p>

## <a name="codex.api/operations">`operations`</a>
``` clojure
(operations)
(operations pred)
```
Function.

Return the operation catalog, optionally filtered by a descriptor predicate.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/api.clj#L12-L14">Source</a></sub></p>

## <a name="codex.api/pages">`pages`</a>
``` clojure
(pages conn operation)
```
Function.

Return a reducible of pages. Reduction performs I/O and stops at reduced or a nil cursor.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/api.clj#L85-L100">Source</a></sub></p>

## <a name="codex.api/provenance">`provenance`</a>
``` clojure
(provenance)
```
Function.

Return the Codex generator version and schema digest.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/api.clj#L17-L17">Source</a></sub></p>

## <a name="codex.api/submit!">`submit!`</a>
``` clojure
(submit! conn operation)
(submit! conn {:keys [op args], :or {args {}}} opts)
```
Function.

Submit {:op keyword :args map} and return a pending operation.
   opts contains local RPC controls such as :timeout-ms; it is not sent as API arguments.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/api.clj#L55-L61">Source</a></sub></p>

-----
# <a name="codex.app">codex.app</a>


Connector metadata and effective runtime state.




## <a name="codex.app/installed!">`installed!`</a>
``` clojure
(installed! c)
(installed! c opts)
```
Function.

Read enabled/callable app runtime state.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app.clj#L6-L7">Source</a></sub></p>

## <a name="codex.app/list!">`list!`</a>
``` clojure
(list! c)
(list! c opts)
```
Function.

Fetch a page of available apps.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app.clj#L2-L3">Source</a></sub></p>

## <a name="codex.app/read!">`read!`</a>
``` clojure
(read! c ids)
(read! c ids opts)
```
Function.

Read app metadata, preserving missing IDs.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app.clj#L4-L5">Source</a></sub></p>

-----
# <a name="codex.app-server">codex.app-server</a>


Bidirectional app-server connections. Raw params/results use string-keyed JSON maps.




## <a name="codex.app-server/abandon!">`abandon!`</a>
``` clojure
(abandon! pending)
```
Function.

Release local pending state. Does not cancel remote work.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app_server.clj#L159-L164">Source</a></sub></p>

## <a name="codex.app-server/await!">`await!`</a>
``` clojure
(await! pending)
(await! pending timeout-ms timeout-value)
```
Function.

Await a pending RPC. A local timeout returns timeout-value without cancelling the RPC.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app_server.clj#L154-L157">Source</a></sub></p>

## <a name="codex.app-server/close!">`close!`</a>
``` clojure
(close! conn)
(close! conn cause)
```
Function.

Close the connection and owned process. Remote turns are not interrupted. Idempotent.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app_server.clj#L191-L209">Source</a></sub></p>

## <a name="codex.app-server/connect!">`connect!`</a>
``` clojure
(connect! {:keys [transport client-info capabilities handlers], :as opts})
```
Function.

Connect over :stdio or :websocket and complete initialize/initialized.
   WebSockets use TCP unless transport contains :unix-socket (ws:// only).
   :handlers maps raw method strings to functions returning {:result ...}, {:error ...},
   or ::defer. :interaction-timeout-ms bounds deferred requests. No account login occurs.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app_server.clj#L211-L262">Source</a></sub></p>

## <a name="codex.app-server/info">`info`</a>
``` clojure
(info conn)
```
Function.

Return initialization results, capabilities, and safe connection metadata.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app_server.clj#L18-L21">Source</a></sub></p>

## <a name="codex.app-server/listen!">`listen!`</a>
``` clojure
(listen! conn f)
(listen! conn {:keys [capacity on-error], :or {capacity 1024}} f)
```
Function.

Observe raw incoming envelopes on an ordered bounded worker queue.
   opts: :capacity (default 1024), :on-error. Returns a subscription.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app_server.clj#L31-L53">Source</a></sub></p>

## <a name="codex.app-server/notify!">`notify!`</a>
``` clojure
(notify! conn method params)
```
Function.

Send a raw notification. Use ::omit to omit params.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app_server.clj#L187-L189">Source</a></sub></p>

## <a name="codex.app-server/pending-requests">`pending-requests`</a>
``` clojure
(pending-requests conn)
```
Function.

Return pending server requests, including their reply tokens.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app_server.clj#L77-L78">Source</a></sub></p>

## <a name="codex.app-server/reply!">`reply!`</a>
``` clojure
(reply! conn token reply)
```
Function.

Reply once to a server request token. reply is {:result wire-value} or {:error wire-error}.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app_server.clj#L80-L91">Source</a></sub></p>

## <a name="codex.app-server/request!">`request!`</a>
``` clojure
(request! conn method params)
(request! conn method params opts)
```
Function.

Submit a raw RPC and return a pending handle. :timeout-ms nil disables its deadline.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app_server.clj#L166-L185">Source</a></sub></p>

## <a name="codex.app-server/status">`status`</a>
``` clojure
(status conn)
```
Function.

Return the connection lifecycle state.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app_server.clj#L17-L17">Source</a></sub></p>

## <a name="codex.app-server/unlisten!">`unlisten!`</a>
``` clojure
(unlisten! subscription)
```
Function.

Stop a local raw observer. Does not unsubscribe a remote thread.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/app_server.clj#L23-L29">Source</a></sub></p>

-----
# <a name="codex.command">codex.command</a>


Sandboxed command execution. Streaming handles preserve byte boundaries.




## <a name="codex.command/await!">`await!`</a>
``` clojure
(await! p)
(await! p ms timeout-value)
```
Function.

Await exit and collected byte output. A local timeout does not terminate the command.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/command.clj#L14-L15">Source</a></sub></p>

## <a name="codex.command/exec!">`exec!`</a>
``` clojure
(exec! c args)
```
Function.

Run a buffered command and return stdout/stderr strings and exit status.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/command.clj#L4-L7">Source</a></sub></p>

## <a name="codex.command/output">`output`</a>
``` clojure
(output p)
```
Function.

Snapshot collected stdout/stderr bytes and truncation status.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/command.clj#L16-L16">Source</a></sub></p>

## <a name="codex.command/resize!">`resize!`</a>
``` clojure
(resize! p size)
```
Function.

Resize a PTY with {:rows n :cols n}.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/command.clj#L12-L12">Source</a></sub></p>

## <a name="codex.command/start!">`start!`</a>
``` clojure
(start! c args)
(start! c args opts)
```
Function.

Start a streaming command. opts: :on-output, :capture-limit-bytes (default 1 MiB).
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/command.clj#L8-L9">Source</a></sub></p>

## <a name="codex.command/terminate!">`terminate!`</a>
``` clojure
(terminate! p)
```
Function.

Request command termination.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/command.clj#L13-L13">Source</a></sub></p>

## <a name="codex.command/write!">`write!`</a>
``` clojure
(write! p bytes)
(write! p bytes close?)
```
Function.

Write stdin bytes; nil bytes with close? closes stdin.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/command.clj#L10-L11">Source</a></sub></p>

-----
# <a name="codex.config">codex.config</a>


Effective configuration and persistent edits.




## <a name="codex.config/patch!">`patch!`</a>
``` clojure
(patch! c edits)
(patch! c edits opts)
```
Function.

Apply a vector of configuration edits atomically.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/config.clj#L7-L8">Source</a></sub></p>

## <a name="codex.config/read!">`read!`</a>
``` clojure
(read! c)
(read! c opts)
```
Function.

Read effective configuration, optionally with layers.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/config.clj#L2-L2">Source</a></sub></p>

## <a name="codex.config/requirements!">`requirements!`</a>
``` clojure
(requirements! c)
```
Function.

Read managed requirements.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/config.clj#L3-L3">Source</a></sub></p>

## <a name="codex.config/write!">`write!`</a>
``` clojure
(write! c path value)
(write! c path value opts)
```
Function.

Write a configuration value at a wire key path. Preserve user JSON keys.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/config.clj#L4-L6">Source</a></sub></p>

-----
# <a name="codex.event">codex.event</a>


Domain notifications and a pure projection of observed conversation state.




## <a name="codex.event/apply-event">`apply-event`</a>
``` clojure
(apply-event state event)
```
Function.

Pure reducer. State contains :threads, :turns, :items, :item-order, and :pending maps.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/event.clj#L57-L93">Source</a></sub></p>

## <a name="codex.event/listen!">`listen!`</a>
``` clojure
(listen! conn f)
(listen! conn filter f)
```
Function.

Observe matching domain events. :capacity and :on-error configure the local observer.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/event.clj#L37-L43">Source</a></sub></p>

## <a name="codex.event/matches?">`matches?`</a>
``` clojure
(matches? filter event)
```
Function.

Match an event against optional :thread-id, :turn-id, :item-id, and :types.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/event.clj#L32-L36">Source</a></sub></p>

## <a name="codex.event/normalize">`normalize`</a>
``` clojure
(normalize conn raw)
```
Function.

Convert a raw notification/request to a domain envelope. Responses return nil.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/event.clj#L14-L30">Source</a></sub></p>

## <a name="codex.event/terminal?">`terminal?`</a>
``` clojure
(terminal? event)
```
Function.

Is this a terminal turn notification?
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/event.clj#L47-L49">Source</a></sub></p>

## <a name="codex.event/text-delta">`text-delta`</a>
``` clojure
(text-delta event)
```
Function.

Return an agent text delta, or nil.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/event.clj#L45-L46">Source</a></sub></p>

## <a name="codex.event/turn-snapshot">`turn-snapshot`</a>
``` clojure
(turn-snapshot state thread-id turn-id)
```
Function.

Read a turn projection, with items in observed order.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/event.clj#L95-L99">Source</a></sub></p>

## <a name="codex.event/unlisten!">`unlisten!`</a>
``` clojure
(unlisten! subscription)
```
Function.

Release an event observer.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/event.clj#L44-L44">Source</a></sub></p>

-----
# <a name="codex.fs">codex.fs</a>


Files on the server host. Read/write content as bytes, never implicit local paths.




## <a name="codex.fs/copy!">`copy!`</a>
``` clojure
(copy! c args)
```
Function.

Copy paths using explicit sourcePath/destinationPath equivalents.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/fs.clj#L13-L14">Source</a></sub></p>

## <a name="codex.fs/delete!">`delete!`</a>
``` clojure
(delete! c path)
(delete! c path opts)
```
Function.

Remove a server-side path.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/fs.clj#L15-L16">Source</a></sub></p>

## <a name="codex.fs/list!">`list!`</a>
``` clojure
(list! c path)
```
Function.

Read a directory.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/fs.clj#L10-L10">Source</a></sub></p>

## <a name="codex.fs/mkdir!">`mkdir!`</a>
``` clojure
(mkdir! c path)
(mkdir! c path opts)
```
Function.

Create a directory.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/fs.clj#L11-L12">Source</a></sub></p>

## <a name="codex.fs/read!">`read!`</a>
``` clojure
(read! c path)
```
Function.

Read a file as a byte array.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/fs.clj#L4-L6">Source</a></sub></p>

## <a name="codex.fs/stat!">`stat!`</a>
``` clojure
(stat! c path)
```
Function.

Read path metadata.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/fs.clj#L9-L9">Source</a></sub></p>

## <a name="codex.fs/unwatch!">`unwatch!`</a>
``` clojure
(unwatch! c watch-id)
```
Function.

Remove a server-side watch by ID.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/fs.clj#L20-L21">Source</a></sub></p>

## <a name="codex.fs/watch!">`watch!`</a>
``` clojure
(watch! c path)
```
Function.

Create a server-side watch. Subscribe to :fs/changed before calling.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/fs.clj#L17-L19">Source</a></sub></p>

## <a name="codex.fs/write!">`write!`</a>
``` clojure
(write! c path bytes)
```
Function.

Write a byte array to a server-side file.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/fs.clj#L7-L8">Source</a></sub></p>

-----
# <a name="codex.goal">codex.goal</a>


Goal operations and pure budget accounting.




## <a name="codex.goal/clear!">`clear!`</a>
``` clojure
(clear! c t)
```
Function.

Clear a thread's goal.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/goal.clj#L8-L9">Source</a></sub></p>

## <a name="codex.goal/read!">`read!`</a>
``` clojure
(read! c t)
```
Function.

Read a thread's current goal.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/goal.clj#L4-L5">Source</a></sub></p>

## <a name="codex.goal/remaining-tokens">`remaining-tokens`</a>
``` clojure
(remaining-tokens goal)
```
Function.

Remaining token budget, or nil for an unbudgeted goal.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/goal.clj#L10-L11">Source</a></sub></p>

## <a name="codex.goal/set!">`set!`</a>
``` clojure
(set! c t fields)
```
Function.

Set or update a goal. A new objective resets usage accounting.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/goal.clj#L6-L7">Source</a></sub></p>

-----
# <a name="codex.history">codex.history</a>


Stored history queries and explicit context changes.




## <a name="codex.history/compact!">`compact!`</a>
``` clojure
(compact! c t)
```
Function.

Request compaction and return acknowledgement; progress arrives as events.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/history.clj#L9-L10">Source</a></sub></p>

## <a name="codex.history/inject!">`inject!`</a>
``` clojure
(inject! c t items)
```
Function.

Append string-keyed Responses API items without starting a turn.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/history.clj#L11-L12">Source</a></sub></p>

## <a name="codex.history/items!">`items!`</a>
``` clojure
(items! c t)
(items! c t opts)
```
Function.

Fetch a page of persisted items (experimental).
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/history.clj#L7-L8">Source</a></sub></p>

## <a name="codex.history/turns!">`turns!`</a>
``` clojure
(turns! c t)
(turns! c t opts)
```
Function.

Fetch a page of persisted turns (experimental).
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/history.clj#L5-L6">Source</a></sub></p>

-----
# <a name="codex.input">codex.input</a>


Pure constructors for user input. Values can also be written as maps.




## <a name="codex.input/audio">`audio`</a>
``` clojure
(audio url)
```
Function.

Audio URL input.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/input.clj#L7-L7">Source</a></sub></p>

## <a name="codex.input/image">`image`</a>
``` clojure
(image url)
```
Function.

Image URL input.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/input.clj#L5-L5">Source</a></sub></p>

## <a name="codex.input/local-audio">`local-audio`</a>
``` clojure
(local-audio path)
```
Function.

Audio path on the server host.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/input.clj#L8-L8">Source</a></sub></p>

## <a name="codex.input/local-image">`local-image`</a>
``` clojure
(local-image path)
```
Function.

Image path on the server host.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/input.clj#L6-L6">Source</a></sub></p>

## <a name="codex.input/mention">`mention`</a>
``` clojure
(mention name path)
```
Function.

App or other mention reference.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/input.clj#L10-L10">Source</a></sub></p>

## <a name="codex.input/normalize">`normalize`</a>
``` clojure
(normalize x)
```
Function.

Normalize a string, input map, or ordered collection to a vector.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/input.clj#L11-L14">Source</a></sub></p>

## <a name="codex.input/skill">`skill`</a>
``` clojure
(skill name path)
```
Function.

Explicit skill reference. Include its $name in the accompanying text.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/input.clj#L9-L9">Source</a></sub></p>

## <a name="codex.input/text">`text`</a>
``` clojure
(text s)
```
Function.

Text input.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/input.clj#L4-L4">Source</a></sub></p>

-----
# <a name="codex.interaction">codex.interaction</a>


Server-initiated requests with typed replies and explicit local responder ownership.




## <a name="codex.interaction/handle!">`handle!`</a>
``` clojure
(handle! conn kind f)
```
Function.

Install the sole responder for a request kind. f returns response data or ::defer.
   Duplicate handlers are rejected. Returns a registration for unhandle!.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/interaction.clj#L24-L38">Source</a></sub></p>

## <a name="codex.interaction/pending">`pending`</a>
``` clojure
(pending conn)
```
Function.

Return typed pending requests, including reply tokens.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/interaction.clj#L12-L13">Source</a></sub></p>

## <a name="codex.interaction/reject!">`reject!`</a>
``` clojure
(reject! conn request code message)
```
Function.

Send a JSON-RPC error for a pending request.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/interaction.clj#L22-L23">Source</a></sub></p>

## <a name="codex.interaction/resolved?">`resolved?`</a>
``` clojure
(resolved? conn request)
```
Function.

Has this request been answered, expired, or cleared?
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/interaction.clj#L14-L15">Source</a></sub></p>

## <a name="codex.interaction/respond!">`respond!`</a>
``` clojure
(respond! conn request value)
```
Function.

Send a typed response to a pending request exactly once locally.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/interaction.clj#L20-L21">Source</a></sub></p>

## <a name="codex.interaction/unhandle!">`unhandle!`</a>
``` clojure
(unhandle! registration)
```
Function.

Remove a handler registration without replacing another owner.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/interaction.clj#L39-L44">Source</a></sub></p>

-----
# <a name="codex.item">codex.item</a>


Pure queries over immutable item values.




## <a name="codex.item/changes">`changes`</a>
``` clojure
(changes items)
```
Function.

Select file-change items.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/item.clj#L6-L6">Source</a></sub></p>

## <a name="codex.item/commands">`commands`</a>
``` clojure
(commands items)
```
Function.

Select command execution items.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/item.clj#L5-L5">Source</a></sub></p>

## <a name="codex.item/completed?">`completed?`</a>
``` clojure
(completed? item)
```
Function.

Was an item completed, or does it carry a terminal execution status?
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/item.clj#L7-L8">Source</a></sub></p>

## <a name="codex.item/messages">`messages`</a>
``` clojure
(messages items)
```
Function.

Select user and agent message items, preserving order.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/item.clj#L3-L4">Source</a></sub></p>

## <a name="codex.item/text">`text`</a>
``` clojure
(text item)
```
Function.

Return textual content for an item, or nil.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/item.clj#L2-L2">Source</a></sub></p>

-----
# <a name="codex.mcp">codex.mcp</a>


MCP integration through app-server, not a separate MCP client.




## <a name="codex.mcp/call!">`call!`</a>
``` clojure
(call! c args)
```
Function.

Call a tool through a thread's configured MCP server.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/mcp.clj#L4-L4">Source</a></sub></p>

## <a name="codex.mcp/login!">`login!`</a>
``` clojure
(login! c args)
```
Function.

Start an MCP OAuth flow; completion arrives as an event.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/mcp.clj#L5-L5">Source</a></sub></p>

## <a name="codex.mcp/reload!">`reload!`</a>
``` clojure
(reload! c)
```
Function.

Reload MCP configuration from disk.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/mcp.clj#L6-L6">Source</a></sub></p>

## <a name="codex.mcp/resource!">`resource!`</a>
``` clojure
(resource! c args)
```
Function.

Read a resource through an initialized MCP server.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/mcp.clj#L3-L3">Source</a></sub></p>

## <a name="codex.mcp/servers!">`servers!`</a>
``` clojure
(servers! c)
(servers! c opts)
```
Function.

Fetch a page of MCP server status.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/mcp.clj#L2-L2">Source</a></sub></p>

-----
# <a name="codex.model">codex.model</a>


Discover models and provider bounds.




## <a name="codex.model/capabilities!">`capabilities!`</a>
``` clojure
(capabilities! c opts)
```
Function.

Read provider capabilities for model/provider arguments.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/model.clj#L3-L4">Source</a></sub></p>

## <a name="codex.model/list!">`list!`</a>
``` clojure
(list! c)
(list! c opts)
```
Function.

Fetch one model page.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/model.clj#L2-L2">Source</a></sub></p>

-----
# <a name="codex.permission">codex.permission</a>


Permission discovery and pure policy values.




## <a name="codex.permission/external-sandbox">`external-sandbox`</a>
``` clojure
(external-sandbox)
(external-sandbox network-access)
```
Function.

Policy for an externally sandboxed execution host.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/permission.clj#L8-L9">Source</a></sub></p>

## <a name="codex.permission/grant">`grant`</a>
``` clojure
(grant permissions)
(grant permissions scope)
```
Function.

Permission reply value. Only supply a requested subset.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/permission.clj#L10-L11">Source</a></sub></p>

## <a name="codex.permission/profiles!">`profiles!`</a>
``` clojure
(profiles! c)
(profiles! c opts)
```
Function.

Fetch available permission profiles for a working directory.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/permission.clj#L2-L3">Source</a></sub></p>

## <a name="codex.permission/read-only">`read-only`</a>
``` clojure
(read-only)
(read-only network-access)
```
Function.

Read-only sandbox policy with optional boolean network access.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/permission.clj#L4-L5">Source</a></sub></p>

## <a name="codex.permission/workspace-write">`workspace-write`</a>
``` clojure
(workspace-write roots)
(workspace-write roots opts)
```
Function.

Workspace-write policy for explicit server-side roots.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/permission.clj#L6-L7">Source</a></sub></p>

-----
# <a name="codex.plugin">codex.plugin</a>


Plugin operations. Upstream marks these APIs as under development.




## <a name="codex.plugin/install!">`install!`</a>
``` clojure
(install! c args)
```
Function.

Install a plugin by explicit source and name arguments.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/plugin.clj#L4-L4">Source</a></sub></p>

## <a name="codex.plugin/list!">`list!`</a>
``` clojure
(list! c)
(list! c opts)
```
Function.

Discover plugins and marketplaces.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/plugin.clj#L2-L2">Source</a></sub></p>

## <a name="codex.plugin/read!">`read!`</a>
``` clojure
(read! c args)
```
Function.

Read a plugin by explicit source and name arguments.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/plugin.clj#L3-L3">Source</a></sub></p>

## <a name="codex.plugin/skill!">`skill!`</a>
``` clojure
(skill! c args)
```
Function.

Read remote plugin skill content.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/plugin.clj#L6-L6">Source</a></sub></p>

## <a name="codex.plugin/uninstall!">`uninstall!`</a>
``` clojure
(uninstall! c args)
```
Function.

Uninstall a plugin.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/plugin.clj#L5-L5">Source</a></sub></p>

-----
# <a name="codex.process">codex.process</a>


Explicit unsandboxed process control. Requires :experimental-api true.




## <a name="codex.process/await!">`await!`</a>
``` clojure
(await! p)
(await! p ms timeout-value)
```
Function.

Await process exit and collected bytes. Local timeout does not kill the process.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/process.clj#L10-L11">Source</a></sub></p>

## <a name="codex.process/kill!">`kill!`</a>
``` clojure
(kill! p)
```
Function.

Request process termination.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/process.clj#L9-L9">Source</a></sub></p>

## <a name="codex.process/output">`output`</a>
``` clojure
(output p)
```
Function.

Snapshot collected stdout/stderr bytes and truncation status.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/process.clj#L12-L12">Source</a></sub></p>

## <a name="codex.process/resize!">`resize!`</a>
``` clojure
(resize! p size)
```
Function.

Resize a process PTY with {:rows n :cols n}.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/process.clj#L8-L8">Source</a></sub></p>

## <a name="codex.process/start!">`start!`</a>
``` clojure
(start! c args)
(start! c args opts)
```
Function.

Start an unsandboxed process with explicit :command and :cwd. Returns a tracked handle.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/process.clj#L4-L5">Source</a></sub></p>

## <a name="codex.process/write!">`write!`</a>
``` clojure
(write! p bytes)
(write! p bytes close?)
```
Function.

Write process stdin bytes.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/process.clj#L6-L7">Source</a></sub></p>

-----
# <a name="codex.repl">codex.repl</a>


Explicit, bounded REPL observation. Loading this namespace performs no I/O.




## <a name="codex.repl/explain">`explain`</a>
``` clojure
(explain op)
```
Function.

Print and return an operation descriptor.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/repl.clj#L24-L24">Source</a></sub></p>

## <a name="codex.repl/history">`history`</a>
``` clojure
(history watch)
```
Function.

Return the bounded event history of a watcher.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/repl.clj#L19-L19">Source</a></sub></p>

## <a name="codex.repl/inspect">`inspect`</a>
``` clojure
(inspect value)
```
Function.

Print and return a value. Connections use their safe info snapshot.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/repl.clj#L21-L23">Source</a></sub></p>

## <a name="codex.repl/pending">`pending`</a>
``` clojure
(pending conn)
```
Function.

Inspect typed pending server requests.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/repl.clj#L20-L20">Source</a></sub></p>

## <a name="codex.repl/unwatch!">`unwatch!`</a>
``` clojure
(unwatch! watch)
```
Function.

Stop a REPL watcher.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/repl.clj#L18-L18">Source</a></sub></p>

## <a name="codex.repl/watch!">`watch!`</a>
``` clojure
(watch! c)
(watch! c {:keys [history-limit print?], :or {history-limit 200, print? true}, :as opts})
```
Function.

Observe and print domain events. :history-limit defaults to 200; :print? defaults true.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/repl.clj#L5-L17">Source</a></sub></p>

-----
# <a name="codex.review">codex.review</a>


Review targets, submission, and parsing of rendered reports.




## <a name="codex.review/branch">`branch`</a>
``` clojure
(branch name)
```
Function.

Review against a base branch.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/review.clj#L6-L6">Source</a></sub></p>

## <a name="codex.review/commit">`commit`</a>
``` clojure
(commit sha)
(commit sha title)
```
Function.

Review a commit.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/review.clj#L7-L7">Source</a></sub></p>

## <a name="codex.review/custom">`custom`</a>
``` clojure
(custom instructions)
```
Function.

Review using custom instructions.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/review.clj#L8-L8">Source</a></sub></p>

## <a name="codex.review/parse-report">`parse-report`</a>
``` clojure
(parse-report report)
```
Function.

Parse the built-in reviewer's rendered report into plain-keyed data.

  Returns :status, :findings, :overall-explanation, and the original :raw text.
  :parsed means a complete findings block was recognized. :unstructured means
  the text has no recognized block or its format is invalid. An empty findings
  vector alone does not prove a clean review. Priority comes from a [P0]-[P3]
  title prefix. Confidence scores and the overall verdict cannot be recovered.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/review.clj#L37-L63">Source</a></sub></p>

## <a name="codex.review/start!">`start!`</a>
``` clojure
(start! c t target)
(start! c t target opts)
```
Function.

Start a review. Preserve both review-thread-id and the initial turn.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/review.clj#L9-L12">Source</a></sub></p>

## <a name="codex.review/uncommitted">`uncommitted`</a>
``` clojure
(uncommitted)
```
Function.

Review staged, unstaged, and untracked changes.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/review.clj#L5-L5">Source</a></sub></p>

-----
# <a name="codex.run">codex.run</a>


Track a user-input turn through its terminal event. A wait timeout never interrupts work.




## <a name="codex.run/await!">`await!`</a>
``` clojure
(await! run)
(await! run timeout-ms timeout-value)
```
Function.

Return the terminal turn projection, including failed-turn data. Transport failures throw.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/run.clj#L12-L15">Source</a></sub></p>

## <a name="codex.run/close!">`close!`</a>
``` clojure
(close! run)
```
Function.

Stop local observation without interrupting the remote turn.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/run.clj#L16-L19">Source</a></sub></p>

## <a name="codex.run/interrupt!">`interrupt!`</a>
``` clojure
(interrupt! run)
```
Function.

Request interruption of this run's identified turn.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/run.clj#L20-L21">Source</a></sub></p>

## <a name="codex.run/snapshot">`snapshot`</a>
``` clojure
(snapshot run)
```
Function.

Return the current immutable turn projection.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/run.clj#L10-L11">Source</a></sub></p>

## <a name="codex.run/start!">`start!`</a>
``` clojure
(start! conn thread args)
```
Function.

Start and track an ordinary user-input turn. Installs observation before submission.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/run.clj#L23-L60">Source</a></sub></p>

-----
# <a name="codex.schema">codex.schema</a>


Inspect and check the bundled protocol schemas. Checks accept wire JSON values.




## <a name="codex.schema/describe">`describe`</a>
``` clojure
(describe id)
```
Function.

Return a JSON Schema document (string keys).
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/schema.clj#L8-L10">Source</a></sub></p>

## <a name="codex.schema/explain">`explain`</a>
``` clojure
(explain id value)
```
Function.

Return structural schema errors, or an empty vector.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/schema.clj#L11-L12">Source</a></sub></p>

## <a name="codex.schema/schemas">`schemas`</a>
``` clojure
(schemas)
```
Function.

Return descriptors for exported schemas and nested value definitions.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/schema.clj#L5-L7">Source</a></sub></p>

## <a name="codex.schema/valid?">`valid?`</a>
``` clojure
(valid? id value)
```
Function.

Does a wire JSON value satisfy the schema checks?
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/schema.clj#L13-L14">Source</a></sub></p>

-----
# <a name="codex.skill">codex.skill</a>


Discover and configure standalone skills.




## <a name="codex.skill/disable!">`disable!`</a>
``` clojure
(disable! c path)
```
Function.

Disable a skill by server-side path.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/skill.clj#L6-L7">Source</a></sub></p>

## <a name="codex.skill/enable!">`enable!`</a>
``` clojure
(enable! c path)
```
Function.

Enable a skill by server-side path.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/skill.clj#L4-L5">Source</a></sub></p>

## <a name="codex.skill/extra-roots!">`extra-roots!`</a>
``` clojure
(extra-roots! c roots)
```
Function.

Replace process-level extra skill roots.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/skill.clj#L8-L9">Source</a></sub></p>

## <a name="codex.skill/list!">`list!`</a>
``` clojure
(list! c)
(list! c opts)
```
Function.

Discover skills, optionally for :cwds and with :force-reload.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/skill.clj#L2-L3">Source</a></sub></p>

-----
# <a name="codex.thread">codex.thread</a>


Conversation lifecycle. Thread values are immutable and contain no connection.




## <a name="codex.thread/archive!">`archive!`</a>
``` clojure
(archive! c t)
```
Function.

Archive a thread and attempt to archive spawned descendants.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/thread.clj#L25-L26">Source</a></sub></p>

## <a name="codex.thread/delete!">`delete!`</a>
``` clojure
(delete! c t)
```
Function.

Permanently delete a thread and spawned descendants.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/thread.clj#L29-L30">Source</a></sub></p>

## <a name="codex.thread/fork!">`fork!`</a>
``` clojure
(fork! c t)
(fork! c t args)
```
Function.

Fork history, optionally through :last-turn-id.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/thread.clj#L13-L14">Source</a></sub></p>

## <a name="codex.thread/list!">`list!`</a>
``` clojure
(list! c)
(list! c args)
```
Function.

Fetch one page of stored threads.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/thread.clj#L17-L18">Source</a></sub></p>

## <a name="codex.thread/loaded!">`loaded!`</a>
``` clojure
(loaded! c)
(loaded! c args)
```
Function.

Fetch a page of loaded thread IDs.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/thread.clj#L19-L20">Source</a></sub></p>

## <a name="codex.thread/patch!">`patch!`</a>
``` clojure
(patch! c t edits)
```
Function.

Patch persisted metadata. Omitted fields and explicit nil differ.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/thread.clj#L23-L24">Source</a></sub></p>

## <a name="codex.thread/read!">`read!`</a>
``` clojure
(read! c t)
(read! c t args)
```
Function.

Read a stored snapshot without subscribing.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/thread.clj#L15-L16">Source</a></sub></p>

## <a name="codex.thread/ref">`ref`</a>
``` clojure
(ref x)
```
Function.

Extract an explicit thread reference from an ID or thread value.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/thread.clj#L5-L8">Source</a></sub></p>

## <a name="codex.thread/rename!">`rename!`</a>
``` clojure
(rename! c t name)
```
Function.

Set a thread's display name.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/thread.clj#L21-L22">Source</a></sub></p>

## <a name="codex.thread/restore!">`restore!`</a>
``` clojure
(restore! c t)
```
Function.

Restore an archived thread.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/thread.clj#L27-L28">Source</a></sub></p>

## <a name="codex.thread/resume!">`resume!`</a>
``` clojure
(resume! c t)
(resume! c t args)
```
Function.

Load and subscribe to a stored thread.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/thread.clj#L11-L12">Source</a></sub></p>

## <a name="codex.thread/start!">`start!`</a>
``` clojure
(start! conn args)
```
Function.

Create a thread and return its thread/configuration context.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/thread.clj#L9-L10">Source</a></sub></p>

## <a name="codex.thread/unsubscribe!">`unsubscribe!`</a>
``` clojure
(unsubscribe! c t)
```
Function.

Remove this connection's remote thread subscription.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/thread.clj#L31-L32">Source</a></sub></p>

-----
# <a name="codex.turn">codex.turn</a>


Turn control. RPC acknowledgement and turn completion are separate.




## <a name="codex.turn/interrupt!">`interrupt!`</a>
``` clojure
(interrupt! c turn)
```
Function.

Request interruption. Completion arrives through events.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/turn.clj#L20-L21">Source</a></sub></p>

## <a name="codex.turn/ref">`ref`</a>
``` clojure
(ref x)
(ref t turn)
```
Function.

Construct or check a reference containing both thread and turn IDs.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/turn.clj#L5-L10">Source</a></sub></p>

## <a name="codex.turn/start!">`start!`</a>
``` clojure
(start! c t args)
```
Function.

Submit input and return the initial turn snapshot.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/turn.clj#L12-L15">Source</a></sub></p>

## <a name="codex.turn/steer!">`steer!`</a>
``` clojure
(steer! c turn input)
```
Function.

Append input only to the explicitly identified active turn.
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/turn.clj#L16-L19">Source</a></sub></p>

## <a name="codex.turn/terminal?">`terminal?`</a>
``` clojure
(terminal? turn)
```
Function.

Is a turn snapshot terminal?
<p><sub><a href="https://github.com/loganlinn/codex-app-clj/blob/main/src/codex/turn.clj#L11-L11">Source</a></sub></p>
