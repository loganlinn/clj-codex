(ns catalog
  "Build catalog resources from stable and experimental Codex schema exports."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [codex.impl.util :as u]
            [schema-bundle :as bundle]))

(def response-overrides
  {"memory/reset" "MemoryResetResponse"
   "remoteControl/enable" "RemoteControlEnableResponse"
   "remoteControl/disable" "RemoteControlDisableResponse"
   "remoteControl/status/read" "RemoteControlStatusReadResponse"
   "config/mcpServer/reload" "McpServerRefreshResponse"
   "windowsSandbox/readiness" "WindowsSandboxReadinessResponse"
   "account/logout" "LogoutAccountResponse"
   "account/rateLimits/read" "GetAccountRateLimitsResponse"
   "account/usage/read" "GetAccountTokenUsageResponse"
   "account/workspaceMessages/read" "GetWorkspaceMessagesResponse"
   "externalAgentConfig/import/readHistories" "ExternalAgentConfigImportHistoriesReadResponse"
   "config/value/write" "ConfigWriteResponse"
   "config/batchWrite" "ConfigWriteResponse"
   "configRequirements/read" "ConfigRequirementsReadResponse"})

(defn read-json [p]
  (json/parse-string (slurp (str p))))

(defn write-edn! [p value]
  (fs/create-dirs (fs/parent p))
  (spit p (with-out-str (pp/pprint value))))

(defn type-name [s]
  (or (some-> (get s "$ref") (str/split #"/") last)
      (some type-name (get s "anyOf"))))

(defn generate! [stable-dir full-dir version output-dir]
  (let [files (bundle/files full-dir)
        {index :schemas definitions :definitions} (bundle/indexes full-dir)
        stable (read-json (fs/path stable-dir "ClientRequest.json"))
        stable-methods (set (map #(get-in % ["properties" "method" "enum" 0]) (get stable "oneOf")))
        union (fn [file direction]
                (let [root (read-json (fs/path full-dir file))]
                  (for [s (get root "oneOf")
                        :let [method (get-in s ["properties" "method" "enum" 0])
                              params (type-name (get-in s ["properties" "params"]))
                              stable-param (get-in stable ["definitions" params])
                              full-param (get-in root ["definitions" params])
                              extra-fields (when stable-param
                                             (vec (sort (remove (set (keys (get stable-param "properties")))
                                                                (keys (get full-param "properties"))))))
                              result (or (get response-overrides method)
                                         (some-> params (str/replace #"^Nullable" "")
                                                 (str/replace #"Params$" "Response")))]]
                    [(u/operation method)
                     (cond-> {:op (u/operation method) :wire/method method
                              :direction direction :args-schema params
                              :params-required? (boolean (some #{"params"} (get s "required")))
                              :wire/params (get-in s ["properties" "params"])
                              :source-schema (str/replace file #"\.json$" "")
                              :completion :rpc-response :retry :never-automatically
                              :experimental-fields (mapv (comp keyword u/kebab) extra-fields)
                              :experimental? (and (= direction :client->server)
                                                  (not (stable-methods method)))}
                       (and result (contains? index result)) (assoc :result-schema result)
                       (get s "description") (assoc :doc (get s "description"))
                       (#{"plugin/list" "plugin/read" "plugin/install" "plugin/uninstall"} method)
                       (assoc :stability :under-development)
                       (= method "thread/rollback") (assoc :deprecated? true))])))
        client (into (sorted-map) (union "ClientRequest.json" :client->server))
        server (into (sorted-map) (union "ServerRequest.json" :server->client))
        notifications (into (sorted-map) (union "ServerNotification.json" :notification))
        provenance {:codex-version version :sha256 (bundle/schema-sha256 full-dir) :experimental true
                    :command bundle/full-command
                    :stable-sha256 (bundle/file-sha256 (fs/file stable-dir "ClientRequest.json"))
                    :stable-command bundle/stable-command}]
    ;; This subtree is entirely generated. Keep handwritten resources outside it.
    (fs/delete-tree (fs/path output-dir bundle/schema-dir))
    (doseq [[path f] files :let [target (fs/path output-dir bundle/schema-dir path)]]
      (fs/create-dirs (fs/parent target))
      (fs/copy f target))
    (fs/create-dirs (fs/path output-dir "resources/codex"))
    (fs/copy (fs/path stable-dir "ClientRequest.json") (fs/path output-dir "resources/codex/stable-client-request.json") {:replace-existing true})
    (write-edn! (fs/file output-dir "resources/codex/schema-index.edn") index)
    (write-edn! (fs/file output-dir "resources/codex/definition-index.edn") definitions)
    (write-edn! (fs/file output-dir "resources/codex/catalog.edn")
                {:provenance provenance :operations client :server-requests server :notifications notifications})
    (println "Generated" (count client) "operations and" (count index) "schemas.")
    (doseq [[op d] client :when (nil? (:result-schema d))]
      (println "Missing result schema:" op (:args-schema d)))))

(defn -main [stable-dir full-dir version & [output-dir]]
  (generate! stable-dir full-dir version (or output-dir ".")))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
