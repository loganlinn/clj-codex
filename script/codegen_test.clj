(ns codegen-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [catalog :as catalog]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing run-tests]]
            [codegen :as codegen]
            [schema-bundle :as bundle]))

(defn write! [root path content]
  (let [file (fs/file root path)]
    (fs/create-dirs (fs/parent file))
    (spit file content)))

(defn exports! [temp]
  (let [stable (str (fs/path temp "stable"))
        full (str (fs/path temp "full"))
        request {"oneOf" [{"properties" {"method" {"enum" ["example/read"]}
                                         "params" {"$ref" "#/definitions/ExampleParams"}}
                           "required" ["method" "params"]}]
                 "definitions" {"ExampleParams" {"type" "object"}}}]
    (write! stable "ClientRequest.json" (json/generate-string request))
    (doseq [[path document] {"ClientRequest.json" request
                             "ServerRequest.json" {"oneOf" []}
                             "ServerNotification.json" {"oneOf" []}
                             "v2/ExampleResponse.json" {"type" "object"}}]
      (write! full path (json/generate-string document)))
    [stable full]))

(defn with-bundle [f]
  (let [temp (fs/create-temp-dir {:prefix "codex-codegen-test-"})
        root (str (fs/path temp "bundle"))
        [stable full] (exports! temp)
        version (bundle/pinned-version ".")]
    (try
      (write! root bundle/version-file (str version "\n"))
      (binding [*out* (java.io.StringWriter.)]
        (catalog/generate! stable full version root))
      (f {:temp temp :root root :stable stable :full full :version version})
      (finally (fs/delete-tree temp)))))

(deftest complete-file-comparison
  (with-bundle
    (fn [{:keys [temp root]}]
      (let [copy (str (fs/path temp "copy"))]
        (fs/copy-tree root copy)
        (is (every? empty? (vals (codegen/differences root copy))))
        ;; These files have never been tracked by Git; discovery uses the filesystem.
        (write! copy "apis/codex/app-server/v2/Obsolete.json" "{}")
        (write! copy "apis/codex/app-server/.unexpected" "hidden")
        (write! copy "apis/codex/app-server/ClientRequest.json" "changed")
        (write! copy "resources/codex/stable-client-request.json" "changed")
        (fs/delete (fs/path copy "apis/codex/app-server/v2/ExampleResponse.json"))
        (fs/delete (fs/path copy "resources/codex/definition-index.edn"))
        (write! copy "resources/codex/operation-semantics.edn" "handwritten")
        (is (= {:missing ["apis/codex/app-server/v2/ExampleResponse.json"
                          "resources/codex/definition-index.edn"]
                :unexpected ["apis/codex/app-server/.unexpected"
                             "apis/codex/app-server/v2/Obsolete.json"]
                :changed ["apis/codex/app-server/ClientRequest.json"
                          "resources/codex/stable-client-request.json"]}
               (codegen/differences root copy)))))))

(deftest regeneration-removes-obsolete-schemas
  (with-bundle
    (fn [{:keys [root stable full version]}]
      (write! root "apis/codex/app-server/v1/Obsolete.json" "{}")
      (write! root "resources/codex/operation-semantics.edn" "{:handwritten true}\n")
      (write! root "resources/codex/notes.txt" "preserve this too")
      (binding [*out* (java.io.StringWriter.)]
        (catalog/generate! stable full version root))
      (is (not (fs/exists? (fs/path root "apis/codex/app-server/v1/Obsolete.json"))))
      (is (= "{:handwritten true}\n" (slurp (fs/file root "resources/codex/operation-semantics.edn"))))
      (is (= "preserve this too" (slurp (fs/file root "resources/codex/notes.txt"))))
      (is (= version (bundle/pinned-version root)))
      (is (= version (:codex-version (bundle/check! root)))))))

(deftest mismatched-generator-fails-before-writing
  (with-bundle
    (fn [{:keys [root]}]
      (let [before (into {} (for [[path file] (bundle/generated-files root)]
                              [path (slurp file)]))
            calls (atom [])]
        (with-redefs [process/shell (fn [& args]
                                      (swap! calls conj args)
                                      {:out "codex-cli 999.0.0\n"})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"generator version mismatch"
                                (codegen/generate! root root))))
        (is (= [[{:out :string} "codex" "--version"]] @calls))
        (is (= before (into {} (for [[path file] (bundle/generated-files root)]
                                 [path (slurp file)]))))))))

(deftest check-is-read-only-and-cleans-temporary-output
  (with-bundle
    (fn [{:keys [root stable full version]}]
      (let [output (atom nil)
            before (bundle/schema-sha256 root)]
        (with-redefs [codegen/generate! (fn [_ target]
                                          (reset! output target)
                                          (catalog/generate! stable full version target))]
          (binding [*out* (java.io.StringWriter.)]
            (codegen/check! root))
          (is (not (fs/exists? @output)))
          (is (= before (bundle/schema-sha256 root)))
          (write! root "apis/codex/app-server/Untracked.json" "{}")
          (let [before (bundle/schema-sha256 root)]
            (binding [*out* (java.io.StringWriter.)]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Generated files differ"
                                    (codegen/check! root))))
            (is (not (fs/exists? @output)))
            (is (= before (bundle/schema-sha256 root)))))))))

(deftest bundle-integrity
  (doseq [[label mutate] [["generator version" #(write! % bundle/version-file "999.0.0")]
                          ["full schema digest" #(write! % "apis/codex/app-server/ClientRequest.json" "{}")]
                          ["full schema digest" #(fs/delete (fs/path % "apis/codex/app-server/ServerRequest.json"))]
                          ["full schema digest" #(write! % "apis/codex/app-server/Extra.json" "{}")]
                          ["stable schema digest" #(write! % "resources/codex/stable-client-request.json" "{}")]
                          ["schema index" #(write! % "resources/codex/schema-index.edn" "{\"Missing\" \"codex/app-server/missing.json\"}")]
                          ["definition index" #(write! % "resources/codex/definition-index.edn" "{\"ExampleParams\" \"codex/app-server/ServerRequest.json\"}")]
                          ["catalog reference" (fn [root]
                                                 (let [path "resources/codex/catalog.edn"
                                                       catalog (edn/read-string (slurp (fs/file root path)))]
                                                   (write! root path (pr-str (assoc-in catalog [:operations :example/read :args-schema] "Missing")))))]
                          ["stable schema digest" (fn [root]
                                                    (let [path "resources/codex/catalog.edn"
                                                          catalog (edn/read-string (slurp (fs/file root path)))]
                                                      (write! root path (pr-str (update catalog :provenance dissoc :stable-sha256)))))]]]
    (testing label
      (with-bundle
        (fn [{:keys [root version]}]
          (is (= version (:codex-version (bundle/check! root))))
          (mutate root)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo (re-pattern label) (bundle/check! root))))))))

(defn -main []
  (let [{:keys [fail error]} (run-tests 'codegen-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "Codegen tests failed" {:fail fail :error error})))))
