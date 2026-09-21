(ns build-test
  (:require [build :as build]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [deps-deploy.deps-deploy :as deploy]
            [schema-bundle :as bundle]))

(def commit (apply str (repeat 40 "a")))

(defn git-fixture [& args]
  (cond
    (= ["rev-parse" "HEAD"] args) commit
    (= ["rev-parse" "--verify" "refs/tags/v0.1.0^{commit}"] args) commit
    (= "status" (first args)) ""
    :else (throw (ex-info "Missing Git ref" {:args args}))))

(use-fixtures :each
  (fn [test]
    (with-redefs [build/env (constantly nil)
                  build/git git-fixture]
      (test))))

(deftest version-boundaries
  (doseq [tag ["v0.1.0" "v1.2.3-rc.1" "v1.2.3-alpha.0" "v1.2.3-beta.2"]]
    (is (= (subs tag 1) (:version (#'build/release-options {:tag tag})))))
  (doseq [tag [nil "" "0.1.0" "v01.0.0" "v1.0.0-SNAPSHOT" "v1.0.0;echo nope"]]
    (is (thrown? clojure.lang.ExceptionInfo (#'build/release-options {:tag tag}))))
  (is (= "0.1.0-SNAPSHOT" (:version (#'build/snapshot-options {:version "0.1.0-SNAPSHOT"}))))
  (doseq [version [nil "" "0.1.0" "v0.1.0-SNAPSHOT" "01.0.0-SNAPSHOT" "0.1.0-snapshot" "0.1.0-SNAPSHOT/../../x"]]
    (is (thrown? clojure.lang.ExceptionInfo (#'build/snapshot-options {:version version}))))
  (with-redefs [build/env {"RELEASE_TAG" "v0.1.0" "SNAPSHOT_VERSION" "0.1.0-SNAPSHOT"}]
    (is (thrown? clojure.lang.ExceptionInfo (build/publish {:version "0.1.0-SNAPSHOT"})))
    (is (thrown? clojure.lang.ExceptionInfo (build/publish-snapshot {:tag "v0.1.0"})))
    (is (thrown? clojure.lang.ExceptionInfo (build/jar {}))))
  (is (thrown? clojure.lang.ExceptionInfo (build/jar {:tag "v0.1.0" :version "0.1.0-SNAPSHOT"}))))

(deftest source-guards
  (is (= commit (:commit (build/check-release {:tag "v0.1.0"}))))
  (is (thrown? clojure.lang.ExceptionInfo (build/check-release {:tag "v0.2.0"})))
  (testing "Snapshots work without a release tag"
    (is (= commit (:commit (build/check-snapshot {:version "0.1.0-SNAPSHOT"})))))
  (doseq [check [#(build/check-release {:tag "v0.1.0"})
                 #(build/check-snapshot {:version "0.1.0-SNAPSHOT"})]]
    (with-redefs [build/git (fn [& args] (if (= "status" (first args)) " M src/codex/api.clj" (apply git-fixture args)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"clean checkout" (check))))
    (with-redefs [build/env {"GITHUB_SHA" "wrong-commit"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match" (check)))))
  (with-redefs [build/git (fn [& args] (if (= "--verify" (second args)) "wrong-commit" (apply git-fixture args)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not point to HEAD" (build/check-release {:tag "v0.1.0"})))))

(deftest workflow-inputs
  (with-redefs [build/env {"RELEASE_TAG" "v0.1.0" "GITHUB_SHA" commit}]
    (is (= "0.1.0" (:version (build/check-release {})))))
  (with-redefs [build/env {"SNAPSHOT_VERSION" "0.1.0-SNAPSHOT" "GITHUB_SHA" commit
                           "GITHUB_ACTIONS" "true" "GITHUB_REF" "refs/heads/main"}]
    (is (= "0.1.0-SNAPSHOT" (:version (build/check-snapshot {})))))
  (doseq [ref ["refs/heads/feature" "refs/tags/v0.1.0"]]
    (with-redefs [build/env {"SNAPSHOT_VERSION" "0.1.0-SNAPSHOT" "GITHUB_ACTIONS" "true" "GITHUB_REF" ref}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must run from main" (build/check-snapshot {}))))))

(deftest credentials-required
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing CLOJARS_USERNAME" (build/publish {:tag "v0.1.0"})))
  (with-redefs [build/env {"CLOJARS_USERNAME" "fixture"}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing CLOJARS_PASSWORD" (build/publish-snapshot {:version "0.1.0-SNAPSHOT"})))))

(deftest publication-packages
  ;; Exercise both real build paths; replace only the network upload and credentials.
  (doseq [[publish opts version scm] [[build/publish {:tag "v0.1.0"} "0.1.0" "v0.1.0"]
                                      [build/publish-snapshot {:version "0.1.0-SNAPSHOT"} "0.1.0-SNAPSHOT" commit]]]
    (let [uploads (atom [])]
      (with-redefs [build/env {"CLOJARS_USERNAME" "fixture" "CLOJARS_PASSWORD" "fixture"}
                    deploy/deploy #(swap! uploads conj %)]
        (publish opts))
      (is (= 1 (count @uploads)))
      (let [{:keys [artifact pom-file installer]} (first @uploads)
            pom (slurp pom-file)]
        (is (= :remote installer))
        (is (= (str "target/clj-codex-" version ".jar") artifact))
        (is (str/includes? pom "<groupId>com.github.loganlinn</groupId>"))
        (is (str/includes? pom (str "<version>" version "</version>")))
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
          (is (nil? (.getEntry jar "codex/core_test.clj"))))))))

(deftest invalid-schema-bundle-blocks-publication
  (let [uploads (atom [])]
    (with-redefs [bundle/check! (fn [_] (throw (ex-info "Invalid schema bundle" {})))
                  build/env {"CLOJARS_USERNAME" "fixture" "CLOJARS_PASSWORD" "fixture"}
                  deploy/deploy #(swap! uploads conj %)]
      (doseq [[publish opts] [[build/publish {:tag "v0.1.0"}]
                              [build/publish-snapshot {:version "0.1.0-SNAPSHOT"}]]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid schema bundle" (publish opts)))))
    (is (empty? @uploads))))
