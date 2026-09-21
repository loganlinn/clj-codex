(ns codegen
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [catalog :as catalog]
            [clojure.set :as set]
            [clojure.string :as str]
            [schema-bundle :as bundle])
  (:import [java.nio.file Files]
           [java.util Arrays]))

(defn generate! [root output-dir]
  (let [expected (bundle/pinned-version root)
        version-output (str/trim (:out (process/shell {:out :string} "codex" "--version")))
        actual (second (re-matches #"codex-cli (.+)" version-output))]
    (when-not (= expected actual)
      (throw (ex-info (str "Codex generator version mismatch. Install @openai/codex@" expected)
                      {:expected expected :actual version-output})))
    (let [temp (fs/create-temp-dir {:prefix "codex-schema-"})
          stable (str (fs/path temp "stable"))
          full (str (fs/path temp "full"))]
      (try
        (process/shell "codex" "app-server" "generate-json-schema" "--out" stable)
        (process/shell "codex" "app-server" "generate-json-schema" "--experimental" "--out" full)
        (catalog/generate! stable full actual output-dir)
        (finally (fs/delete-tree temp))))))

(defn differences [expected-root actual-root]
  (let [expected (bundle/generated-files expected-root)
        actual (bundle/generated-files actual-root)
        expected-paths (set (keys expected))
        actual-paths (set (keys actual))]
    {:missing (sort (set/difference expected-paths actual-paths))
     :unexpected (sort (set/difference actual-paths expected-paths))
     :changed (sort (for [path (set/intersection expected-paths actual-paths)
                          :when (not (Arrays/equals (Files/readAllBytes (.toPath (get expected path)))
                                                    (Files/readAllBytes (.toPath (get actual path)))))]
                      path))}))

(defn check! [root]
  (let [temp (fs/create-temp-dir {:prefix "codex-codegen-check-"})]
    (try
      (generate! root (str temp))
      (let [diff (differences (str temp) root)]
        (when (some seq (vals diff))
          (doseq [[kind paths] diff path paths]
            (println (str (name kind) ": " path)))
          (throw (ex-info "Generated files differ. Run bb codegen and commit the generated changes." diff))))
      (bundle/check! root)
      (println "Committed schemas and catalogs reproduce exactly.")
      (finally (fs/delete-tree temp)))))

(defn -main [& args]
  (case (vec args)
    [] (generate! "." ".")
    ["--check"] (check! ".")
    (throw (ex-info "Expected no arguments or --check" {:args args}))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
