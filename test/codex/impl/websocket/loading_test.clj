(ns codex.impl.websocket.loading-test
  (:require [clojure.test :refer [deftest is]]
            [codex.impl.websocket.unix :as unix]))

(deftest lazy-runtime-loading
  (is (nil? (find-ns 'codex.impl.websocket.unix-transport))
      "Requiring the facade must not load Java 16 implementation classes")
  (when-not (resolve 'java.net.UnixDomainSocketAddress)
    (is (thrown-with-msg? UnsupportedOperationException #"Java 16"
                         (unix/websocket {:uri "ws://localhost/"
                                          :unix-socket "/unused"})))))
