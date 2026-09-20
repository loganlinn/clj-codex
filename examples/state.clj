#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(require '[examples.support :as example]
         '[clojure.pprint :refer [pprint]]
         '[codex.app-server :as server]
         '[codex.account :as account]
         '[codex.config :as config]
         '[codex.model :as model]
         '[codex.skill :as skill]
         '[codex.mcp :as mcp]
         '[codex.thread :as thread])

(defn state! [{:keys [topic cwd] :as opts}]
  (let [c (example/connect! opts)]
    (try
      (pprint
       (case topic
         :connection (server/info c)
         :account (account/read! c)
         :limits (account/limits! c)
         :config (config/read! c (cond-> {:include-layers true} cwd (assoc :cwd cwd)))
         :models (model/list! c)
         :skills (skill/list! c (cond-> {} cwd (assoc :cwds [cwd])))
         :mcp (mcp/servers! c)
         :loaded (thread/loaded! c)))
      (finally (server/close! c)))))

(when (= *file* (System/getProperty "babashka.file"))
  (example/main! "bb examples/state.clj [TOPIC] [--url ws://localhost:4500]"
                 (merge example/connection-spec
                        {:topic {:coerce :keyword :default :connection
                                 :validate #{:connection :account :limits :config :models :skills :mcp :loaded}
                                 :desc "connection, account, limits, config, models, skills, mcp, or loaded"}
                         :cwd {:coerce :string :desc "Absolute server-side directory for configuration and skills"}})
                 [:topic] state!))
