(ns build-test
  (:require [build :as build]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [deps-deploy.deps-deploy :as deploy]
            [release-version :as version]
            [schema-bundle :as bundle]))

(def commit (apply str (repeat 40 "a")))

(def ^:dynamic *project-version* "0.1.0")

(defn git-fixture [& args]
  (cond
    (= ["rev-parse" "HEAD"] args) commit
    (= ["show" (str commit ":version.edn")] args) (pr-str {:version *project-version*})
    (= ["rev-parse" "--verify" (str "refs/tags/v" *project-version* "^{commit}")] args) commit
    (= "status" (first args)) ""
    :else (throw (ex-info "Missing Git ref" {:args args}))))

(use-fixtures :each
  (fn [test]
    (with-redefs [build/env (constantly nil)
                  build/git git-fixture
                  version/read-version! (fn [_] (version/parse-version *project-version*))]
      (test))))

(deftest canonical-version-and-publication-boundaries
  (doseq [value ["0.1.0" "1.2.3-rc.1" "1.2.3-alpha.0" "1.2.3-beta.2"]]
    (binding [*project-version* value]
      (is (= value (:version (build/check-release {}))))
      (is (= (str "v" value) (:tag (build/check-release {}))))
      (is (thrown? clojure.lang.ExceptionInfo (build/check-snapshot {})))))
  (doseq [value ["0.1.0-SNAPSHOT" "0.1.0-rc.1-SNAPSHOT"]]
    (binding [*project-version* value]
      (is (= value (:version (build/check-snapshot {}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"still has a snapshot" (build/check-release {})))))
  (doseq [opts [{:tag "v0.1.0"} {:version "0.1.0-SNAPSHOT"} {:version nil}]]
    (doseq [operation [build/check-release build/check-snapshot build/jar]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Version overrides" (operation opts)))))
  (with-redefs [build/env {"SNAPSHOT_VERSION" "0.1.0-SNAPSHOT"}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Version overrides" (build/jar {})))))

(deftest source-guards
  (doseq [[value check] [["0.1.0" build/check-release]
                         ["0.1.0-SNAPSHOT" build/check-snapshot]]]
    (binding [*project-version* value]
      (is (= commit (:commit (check {}))))
      (doseq [status [" M src/codex/api.clj" "?? version.edn"]]
        (with-redefs [build/git (fn [& args] (if (= "status" (first args)) status (apply git-fixture args)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"clean checkout" (check {})))))
      (with-redefs [build/env {"GITHUB_SHA" "wrong-commit"}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match" (check {}))))
      (with-redefs [build/git (fn [& args] (if (= "show" (first args)) "{:version \"9.0.0\"}" (apply git-fixture args)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"version committed at HEAD" (check {}))))))
  (with-redefs [build/git (fn [& args] (if (= "--verify" (second args)) "wrong-commit" (apply git-fixture args)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not point to HEAD" (build/check-release {})))))

(deftest workflow-inputs-validate-the-committed-version
  (with-redefs [build/env {"RELEASE_TAG" "v0.1.0" "GITHUB_SHA" commit}]
    (is (= "0.1.0" (:version (build/check-release {})))))
  (doseq [tag ["v0.2.0" "0.1.0" "v0.1.0-rc.1"]]
    (with-redefs [build/env {"RELEASE_TAG" tag}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tag does not match" (build/check-release {})))))
  (binding [*project-version* "0.1.0-SNAPSHOT"]
    (with-redefs [build/env {"RELEASE_TAG" "v0.1.0"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tag does not match" (build/publish {}))))
    (with-redefs [build/env {"GITHUB_SHA" commit "GITHUB_ACTIONS" "true" "GITHUB_REF" "refs/heads/main"}]
      (is (= "0.1.0-SNAPSHOT" (:version (build/check-snapshot {})))))
    (doseq [ref ["refs/heads/feature" "refs/tags/v0.1.0"]]
      (with-redefs [build/env {"GITHUB_ACTIONS" "true" "GITHUB_REF" ref}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must run from main" (build/check-snapshot {})))))))

(deftest credentials-required
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing CLOJARS_USERNAME" (build/publish {})))
  (binding [*project-version* "0.1.0-SNAPSHOT"]
    (with-redefs [build/env {"CLOJARS_USERNAME" "fixture"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing CLOJARS_PASSWORD" (build/publish-snapshot {}))))))

(deftest publication-packages
  ;; Exercise both real build paths; replace only the network upload and credentials.
  (doseq [[publish value scm] [[build/publish "0.1.0" "v0.1.0"]
                               [build/publish-snapshot "0.1.0-SNAPSHOT" commit]]]
    (binding [*project-version* value]
      (let [uploads (atom [])]
        (with-redefs [build/env {"CLOJARS_USERNAME" "fixture" "CLOJARS_PASSWORD" "fixture"}
                      deploy/deploy #(swap! uploads conj %)]
          (publish {}))
        (is (= 1 (count @uploads)))
        (let [{:keys [artifact pom-file installer]} (first @uploads)
              pom (slurp pom-file)]
          (is (= :remote installer))
          (is (= (str "target/clj-codex-" value ".jar") artifact))
          (is (str/includes? pom "<groupId>com.github.loganlinn</groupId>"))
          (is (str/includes? pom (str "<version>" value "</version>")))
          (is (str/includes? pom (str "<tag>" scm "</tag>")))
          (is (str/includes? pom "MIT License"))
          (is (not (str/includes? pom "io.github.loganlinn")))
          (is (not (str/includes? pom "tools.build")))
          (is (not (str/includes? pom "deps-deploy")))
          (with-open [jar (java.util.jar.JarFile. artifact)]
            (doseq [root ["src" "resources" "apis"]
                    file (file-seq (io/file root))
                    :when (.isFile file)]
              (let [entry (.getEntry jar (str (.relativize (.toPath (io/file root)) (.toPath file))))]
                (is (some? entry))
                (when entry
                  (with-open [stream (.getInputStream jar entry)]
                    (is (java.util.Arrays/equals (java.nio.file.Files/readAllBytes (.toPath file))
                                                 (.readAllBytes stream)))))))
            (is (some? (.getEntry jar "META-INF/LICENSE")))
            (is (some? (.getEntry jar "META-INF/maven/com.github.loganlinn/clj-codex/pom.xml")))
            (is (nil? (.getEntry jar "build.clj")))
            (is (nil? (.getEntry jar "codex/core_test.clj")))))))))

(deftest invalid-schema-bundle-blocks-publication
  (let [uploads (atom [])]
    (with-redefs [bundle/check! (fn [_] (throw (ex-info "Invalid schema bundle" {})))
                  build/env {"CLOJARS_USERNAME" "fixture" "CLOJARS_PASSWORD" "fixture"}
                  deploy/deploy #(swap! uploads conj %)]
      (doseq [[publish value] [[build/publish "0.1.0"]
                               [build/publish-snapshot "0.1.0-SNAPSHOT"]]]
        (binding [*project-version* value]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid schema bundle" (publish {}))))))
    (is (empty? @uploads))))
