(ns schema-bundle
  "Read-only integrity checks shared by code generation and packaging."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files]
           [java.security MessageDigest]))

(def schema-dir "apis/codex/app-server")

(def version-file "resources/codex/generator-version.txt")

(def resource-files
  ["resources/codex/catalog.edn"
   "resources/codex/schema-index.edn"
   "resources/codex/definition-index.edn"
   "resources/codex/stable-client-request.json"])

(def full-command "codex app-server generate-json-schema --experimental")

(def stable-command "codex app-server generate-json-schema")

(defn pinned-version [root]
  (let [version (str/trim (slurp (io/file root version-file)))]
    (when-not (re-matches #"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?" version)
      (throw (ex-info "Expected an exact Codex version in generator-version.txt" {:version version})))
    version))

(defn files
  "Return every file, including hidden files, keyed by its relative path."
  [root]
  (let [root (io/file root)]
    (into (sorted-map)
          (for [file (file-seq root) :when (.isFile file)]
            [(str/replace (str (.relativize (.toPath root) (.toPath file))) "\\" "/") file]))))

(defn generated-files [root]
  (into (sorted-map)
        (concat (for [[path file] (files (io/file root schema-dir))]
                  [(str schema-dir "/" path) file])
                (for [path resource-files
                      :let [file (io/file root path)]
                      :when (.isFile file)]
                  [path file]))))

(defn- hex-digest [digest]
  (apply str (map #(format "%02x" (bit-and 255 %)) (.digest digest))))

(defn file-sha256 [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (Files/readAllBytes (.toPath (io/file file))))
    (hex-digest digest)))

(defn schema-sha256
  "Hash sorted relative paths and file bytes, preserving the original full-export digest format."
  [root]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [[path file] (files root)]
      (.update digest (.getBytes path "UTF-8"))
      (.update digest (Files/readAllBytes (.toPath file))))
    (hex-digest digest)))

(defn indexes [root]
  (let [documents (into (sorted-map)
                        (for [[path file] (files root)]
                          [path (json/parse-string (slurp file))]))]
    {:schemas (into (sorted-map)
                    (for [[path _] documents]
                      [(str/replace (.getName (io/file path)) #"\.json$" "")
                       (str "codex/app-server/" path)]))
     :definitions (reduce (fn [index [path document]]
                            (reduce (fn [index name]
                                      (if (contains? index name) index
                                          (assoc index name (str "codex/app-server/" path))))
                                    index (keys (get document "definitions"))))
                          (sorted-map)
                          (sort-by (fn [[path _]] [(if (str/starts-with? path "v2/") 0 1) path])
                                   documents))}))

(defn- expect! [label expected actual]
  (when-not (= expected actual)
    (throw (ex-info (str "Schema bundle integrity check failed: " label)
                    {:check label :expected expected :actual actual}))))

(defn check!
  "Check committed provenance and index references without invoking Codex or writing files."
  [root]
  (let [read-edn #(edn/read-string (slurp (io/file root "resources/codex" %)))
        catalog (read-edn "catalog.edn")
        provenance (:provenance catalog)
        full (io/file root schema-dir)
        stable (io/file root "resources/codex/stable-client-request.json")]
    (expect! "generator version" (pinned-version root) (:codex-version provenance))
    (expect! "experimental export" true (:experimental provenance))
    (expect! "full schema command" full-command (:command provenance))
    (expect! "stable schema command" stable-command (:stable-command provenance))
    (expect! "full schema digest" (schema-sha256 full) (:sha256 provenance))
    (expect! "stable schema digest" (file-sha256 stable) (:stable-sha256 provenance))
    (let [{:keys [schemas definitions]} (indexes full)]
      (expect! "schema index" schemas (read-edn "schema-index.edn"))
      (expect! "definition index" definitions (read-edn "definition-index.edn"))
      (doseq [group [:operations :server-requests :notifications]
              [op descriptor] (get catalog group)
              field [:source-schema :args-schema :result-schema]
              :let [reference (get descriptor field)]
              :when reference]
        (when-not (or (contains? schemas reference) (contains? definitions reference))
          (throw (ex-info "Schema bundle integrity check failed: catalog reference"
                          {:group group :op op :field field :reference reference})))))
    provenance))
