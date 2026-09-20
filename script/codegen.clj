(require '[babashka.fs :as fs] '[babashka.process :as process] '[clojure.string :as str])
(let [temp (fs/create-temp-dir {:prefix "codex-schema-"})
      stable (str (fs/path temp "stable"))
      full (str (fs/path temp "full"))
      version (str/replace (str/trim (:out (process/shell {:out :string} "codex" "--version"))) #"^codex-cli " "")]
  (try
    (process/shell "codex" "app-server" "generate-json-schema" "--out" stable)
    (process/shell "codex" "app-server" "generate-json-schema" "--experimental" "--out" full)
    (binding [*command-line-args* [stable full version]] (load-file "script/catalog.clj"))
    (finally (fs/delete-tree temp))))
