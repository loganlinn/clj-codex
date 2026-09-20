# Provenance

Extracted on 2026-09-19 from the uncommitted Unix WebSocket implementation in:

`/Users/logan/src/github.com/babashka/http-client`

The source checkout's base HEAD was
`ca3665c622dd299a2feaa9c8e07d8d137a6037a6`.
The Unix implementation was not part of that commit.

SOURCE_HASHES.edn records SHA-256 hashes of the exact source files read during
extraction. The included LICENSE is copied verbatim from that checkout,
including its existing copyright placeholder.

The implementation and tests were moved into `codex.impl.websocket.*`.
Two URI/header helpers were made local to the implementation.
Worker names now use `codex-unix-websocket-`.
The tests use the private facade and no longer reload HTTP-client namespaces.
A small facade and standalone test runner were added.

The frame engine and protocol behavior were preserved during extraction.
No source or configuration files in either existing checkout were changed
by this extraction.
