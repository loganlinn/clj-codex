(ns codex.mcp "MCP integration through app-server, not a separate MCP client." (:require [codex.api :as api]))

(defn servers!
  "Fetch a page of MCP server status."
  ([c] (servers! c {}))
  ([c opts] (api/invoke! c {:op :mcp-server-status/list :args opts})))

(defn resource!
  "Read a resource through an initialized MCP server."
  [c args]
  (api/invoke! c {:op :mcp-server.resource/read :args args}))

(defn call!
  "Call a tool through a thread's configured MCP server."
  [c args]
  (api/invoke! c {:op :mcp-server.tool/call :args args}))

(defn login!
  "Start an MCP OAuth flow; completion arrives as an event."
  [c args]
  (api/invoke! c {:op :mcp-server.oauth/login :args args}))

(defn reload!
  "Reload MCP configuration from disk."
  [c]
  (api/invoke! c {:op :config.mcp-server/reload}))
