(ns codex.impl.util
  (:require [clojure.string :as str]))

(defn kebab [s]
  (-> s (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace "_" "-") str/lower-case))

(defn operation [method]
  (let [parts (map kebab (str/split method #"/"))]
    (if (next parts)
      (keyword (str/join "." (butlast parts)) (last parts))
      (keyword (first parts)))))

(defn error [category message data]
  (ex-info message (assoc data :codex.error/category category)))

(defn uuid [] (str (java.util.UUID/randomUUID)))

(defn worker! [name f]
  (doto (Thread. ^Runnable (fn [] (f)) ^String name)
    (.setDaemon true)
    (.start)))

(defn deliver-error! [p e] (deliver p {:error e}))
(defn deliver-value! [p v] (deliver p {:value v}))
(defn unwrap [v] (if-let [e (:error v)] (throw e) (:value v)))

(defn await-result
  ([p] (unwrap @p))
  ([p timeout-ms timeout-value]
   (let [sentinel (Object.) v (deref p timeout-ms sentinel)]
     (if (identical? sentinel v) timeout-value (unwrap v)))))
