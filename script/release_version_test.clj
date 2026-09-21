(ns release-version-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [release-version :as version]))

(defn with-version-file! [value f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "codex-version-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file root version/version-file)]
    (try
      (spit file (str (pr-str {:version value}) "\n"))
      (f root file)
      (finally
        (doseq [file (reverse (file-seq root))]
          (io/delete-file file))))))

(deftest version-syntax-and-derived-tags
  (doseq [base ["0.1.0" "1.2.3-alpha.0" "1.2.3-beta.2" "1.2.3-rc.1"]
          snapshot? [false true]]
    (let [value (str base (when snapshot? "-SNAPSHOT"))
          parsed (version/parse-version value)]
      (is (= value (:version parsed)))
      (is (= (str "v" base) (:tag parsed)))
      (is (= snapshot? (:snapshot? parsed)))
      (is (= (not= base "0.1.0") (:prerelease? parsed)))))
  (doseq [value [nil "" "v0.1.0" "01.0.0" "1.0" "1.0.0-preview.1" "1.0.0-rc.01"
                 "1.0.0-snapshot" "1.0.0-SNAPSHOT-rc.1" "1.0.0-SNAPSHOT-SNAPSHOT"
                 "1.0.0;echo nope" "1.0.0-SNAPSHOT/../../x"]]
    (is (thrown? clojure.lang.ExceptionInfo (version/parse-version value)))))

(deftest version-file-is-the-source
  (with-version-file! "2.3.4-SNAPSHOT"
    (fn [root file]
      (is (= "2.3.4-SNAPSHOT" (:version (version/read-version! root))))
      (is (nil? (version/check-committed! root "{:version \"2.3.4-SNAPSHOT\"}")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match"
                            (version/check-committed! root "{:version \"2.3.3-SNAPSHOT\"}")))
      (doseq [contents ["" "nil" "\"2.3.4\"" "{}" "{:version nil}"
                        "{:version \"2.3.4\" :other true}"
                        "{:version \"2.3.4\"} {:version \"2.3.5\"}"]]
        (spit file contents)
        (is (thrown? clojure.lang.ExceptionInfo (version/read-version! root))))
      (io/delete-file file)
      (is (thrown? java.io.FileNotFoundException (version/read-version! root))))))

(deftest prepare-removes-only-the-snapshot-suffix
  (doseq [base ["0.1.0" "0.1.0-rc.1"]]
    (with-version-file! (str base "-SNAPSHOT")
      (fn [root file]
        (is (= base (:version (version/prepare! root))))
        (is (= (str (pr-str {:version base}) "\n") (slurp file)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must contain a snapshot"
                              (version/prepare! root)))
        (is (= base (:version (version/read-version! root))))))))

(deftest bump-starts-a-new-snapshot
  (doseq [[start part expected] [["1.2.3" "patch" "1.2.4-SNAPSHOT"]
                                 ["1.2.3" "minor" "1.3.0-SNAPSHOT"]
                                 ["1.2.3" "major" "2.0.0-SNAPSHOT"]
                                 ["1.2.3-SNAPSHOT" "minor" "1.3.0-SNAPSHOT"]
                                 ["1.2.3-rc.1" "patch" "1.2.4-SNAPSHOT"]]]
    (with-version-file! start
      (fn [root _]
        (is (= expected (:version (version/bump! root part))))
        (is (= expected (:version (version/read-version! root)))))))
  (with-version-file! "1.2.3"
    (fn [root file]
      (let [before (slurp file)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Expected patch"
                              (version/bump! root "9.0.0")))
        (is (= before (slurp file)))))))

(deftest task-arguments-cannot-set-an-arbitrary-version
  (doseq [args [["show" "1.0.0"] ["prepare" "1.0.0"] ["bump" "patch" "extra"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Usage:" (apply version/-main args)))))
