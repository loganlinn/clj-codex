(ns examples.repl
  (:require [codex.api :as api]
            [clojure.pprint :refer [pprint]]
            [codex.app-server :as server]
            [codex.account :as account]
            [codex.command :as command]
            [codex.config :as config]
            [codex.input :as input]
            [codex.interaction :as interaction]
            [codex.item :as item]
            [codex.model :as model]
            [codex.repl :as repl]
            [codex.run :as run]
            [codex.schema :as schema]
            [codex.thread :as thread]
            [codex.turn :as turn]))

(comment
  ;; From the checkout: bb --classpath .:src:resources:apis repl
  ;; (require 'examples.repl) then (in-ns 'examples.repl).
  ;; Evaluate individual forms. Loading this file performs no API calls.
  (def conn (server/connect! {:transport {:type :stdio :command ["codex" "app-server"]}
                              :capabilities {:experimental-api true}}))
  ;; Alternatively, connect to an existing server instead of creating conn above:
  (def conn (server/connect! {:transport {:type :websocket :url "ws://127.0.0.1:4500"}
                              :capabilities {:experimental-api true}}))

  ;; Connection-local state, configured account, effective configuration, models.
  (server/info conn)
  (account/read! conn)
  (config/read! conn {:include-layers true})
  (model/list! conn)

  ;; Discover operations and argument schemas without a server.
  (api/describe :thread/start)
  (api/operations :experimental?)
  (schema/describe "ThreadReadParams")
  (schema/valid? "ThreadReadParams" {"threadId" "example" "includeTurns" true})

  ;; Stored threads differ from this server process's loaded threads.
  (def recent (into [] (take 10) (api/entries conn {:op :thread/list :args {:limit 25}})))
  (mapv #(select-keys % [::thread/id ::thread/name ::thread/cwd ::thread/status]) recent)
  (thread/loaded! conn)
  (def stored
    (when-let [t (first recent)]
      (thread/read! conn t {:include-turns true})))
  (keys stored)
  (mapv #(select-keys % [::turn/id ::turn/status]) (::thread/turns stored))
  (-> stored ::thread/turns first ::turn/items pprint)

  ;; Raw RPC: wire method strings, string keys, and an explicit pending result.
  (def pending (server/request! conn "model/list" {"limit" 5}))
  (server/await! pending)

  ;; Create work only when you want to run the model.
  (def context (thread/start! conn {:cwd (System/getProperty "user.dir")
                                    :sandbox :read-only :approval-policy :never}))
  (def id (get-in context [::thread/thread ::thread/id]))
  (def watch (repl/watch! conn {:thread-id id}))
  (def work (run/start! conn id {:input [(input/text "Explain this project.")]}))
  (run/snapshot work)
  (def result (run/await! work 60000 ::still-running))
  (when (map? result) (item/messages (::turn/items result)))
  (repl/history watch)
  ;; A wait timeout leaves the turn running. Interrupt explicitly if needed.
  (run/interrupt! work)

  ;; Deferred approvals: install before work on a thread whose policy asks.
  ;; The read-only / never thread above does not request sandbox escalation.
  (def handler
    (interaction/handle! conn :item.command-execution/request-approval
                         (constantly ::interaction/defer)))
  (repl/pending conn)
  (when-let [request (first (filter #(= :item.command-execution/request-approval (:kind %))
                                    (interaction/pending conn)))]
    (interaction/respond! conn request {:decision :decline}))
  (interaction/unhandle! handler)

  ;; Run a server-side command and inspect its result as data.
  (command/exec! conn {:command ["git" "status" "--short"]
                       :cwd (System/getProperty "user.dir")})

  (repl/unwatch! watch)
  (run/close! work)
  (server/close! conn))
