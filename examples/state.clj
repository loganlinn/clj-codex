#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(ns examples.state
  "Read connection, account, configuration, and server state as EDN.

  bb examples/state.clj connection
  bb examples/state.clj account
  bb examples/state.clj limits
  bb examples/state.clj config --cwd /absolute/path/to/project
  bb examples/state.clj models
  bb examples/state.clj skills --cwd /absolute/path/to/project
  bb examples/state.clj mcp
  bb examples/state.clj loaded --url ws://127.0.0.1:4500

  Available topics depend on the server version and configured provider.
  Account rate limits require a supported account. Models, MCP status, and
  loaded threads return one page. See examples.threads for pagination.
  Configuration output can contain local paths and provider settings.

  Loaded threads belong to the connected server process. A new stdio server
  does not expose another process's live state. Shared connection options
  and instructions for an existing WebSocket server are in examples.support."
  (:use examples.support)
  (:require [clojure.pprint :refer [pprint]]
            [codex.app-server :as server]
            [codex.account :as account]
            [codex.config :as config]
            [codex.model :as model]
            [codex.skill :as skill]
            [codex.mcp :as mcp]
            [codex.thread :as thread]))

(defn state! [{:keys [topic cwd] :as opts}]
  (server/with-connection [c (connect! opts)]
    (pprint
     (case topic
       :connection (server/info c)
       :account (account/read! c)
       :limits (account/limits! c)
       :config (config/read! c (cond-> {:include-layers true} cwd (assoc :cwd cwd)))
       :models (model/list! c)
       :skills (skill/list! c (cond-> {} cwd (assoc :cwds [cwd])))
       :mcp (mcp/servers! c)
       :loaded (thread/loaded! c)))))

(when (= *file* (System/getProperty "babashka.file"))
  (main! "bb examples/state.clj [TOPIC] [--url ws://localhost:4500]"
         (merge connection-spec
                {:topic {:coerce :keyword :default :connection
                         :validate #{:connection :account :limits :config :models :skills :mcp :loaded}
                         :desc "connection, account, limits, config, models, skills, mcp, or loaded"}
                 :cwd {:coerce :string :desc "Absolute server-side directory for configuration and skills"}})
         [:topic] state!))
