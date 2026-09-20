(ns examples.support
  "Small helpers shared by the Babashka examples. Loading this file starts no server."
  (:require [babashka.classpath :as cp]
            [babashka.cli :as cli]
            [babashka.fs :as fs]
            [clojure.string :as str]))

;; Resolve the checkout from this file, so scripts also work from another repo.
(let [root (fs/parent (fs/parent (fs/absolutize *file*)))]
  (cp/add-classpath
    (str/join fs/path-separator (map #(str (fs/path root %)) ["src" "resources" "apis"]))))

(require '[codex.app-server :as server])

(def connection-spec
  {:url {:coerce :string :desc "Connect to an existing ws:// or wss:// server"}
   :token-env {:coerce :string :desc "Environment variable with the WebSocket token"}
   :experimental {:coerce :boolean :desc "Opt into experimental API operations"}})

(def timeout-spec
  {:timeout-ms {:coerce :long :default 300000 :validate pos?
                :desc "Maximum wait in milliseconds"}})

(defn main!
  "Parse CLI options, print help, and report failures with a nonzero exit status."
  [usage spec args->opts f]
  (try
    (let [spec (assoc spec :help {:alias :h :coerce :boolean :desc "Show help"})
          {:keys [opts args]} (cli/parse-args *command-line-args*
                                {:spec spec :args->opts args->opts :restrict true})]
      (cond
        (:help opts) (do (println usage) (println) (println (cli/format-opts {:spec spec})))
        (seq args) (throw (ex-info "Unexpected positional arguments" {:args args}))
        :else (f opts)))
    (catch Exception e
      (binding [*out* *err*]
        (println "Error:" (ex-message e))
        (when-let [data (ex-data e)] (prn data)))
      (System/exit 1))))

(defn connect! [{:keys [url token-env experimental cwd]}]
  (when (and token-env (not url))
    (throw (ex-info "--token-env requires --url" {})))
  (let [token (when token-env (System/getenv token-env))]
    (when (and token-env (str/blank? token))
      (throw (ex-info "The token environment variable is empty" {:variable token-env})))
    (server/connect!
      {:transport (if url
                    (cond-> {:type :websocket :url url}
                      token-env (assoc :token-fn (constantly token)))
                    (cond-> {:type :stdio :command ["codex" "app-server"]}
                      cwd (assoc :cwd cwd)))
       :client-info {:name "clojure-examples" :version "0.1.0"}
       :capabilities {:experimental-api (boolean experimental)}})))

(defn successful! [turn]
  (when-not (= :completed (:codex.turn/status turn))
    (throw (ex-info "Turn did not complete successfully" {:turn turn})))
  turn)
