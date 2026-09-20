(ns codex.turn
  "Turn control. RPC acknowledgement and turn completion are separate."
  (:refer-clojure :exclude [ref])
  (:require [codex.api :as api] [codex.input :as input] [codex.thread :as thread] [codex.impl.util :as u]))
(defn ref "Construct or check a reference containing both thread and turn IDs."
  ([x]
   (when-not (and (string? (::thread/id x)) (seq (::thread/id x)) (string? (::id x)) (seq (::id x)))
     (throw (u/error :argument "A turn reference requires thread and turn IDs" {})))
   (select-keys x [::thread/id ::id]))
  ([t turn] (ref (assoc (thread/ref t) ::id (if (string? turn) turn (::id turn))))))
(defn terminal? "Is a turn snapshot terminal?" [turn] (contains? #{:completed :failed :interrupted} (::status turn)))
(defn start! "Submit input and return the initial turn snapshot." [c t args]
  (api/invoke! c {:op :turn/start
                  :args (cond-> (assoc args :thread-id (::thread/id (thread/ref t)))
                          (contains? args :input) (update :input input/normalize))}))
(defn steer! "Append input only to the explicitly identified active turn." [c turn input]
  (let [reference (ref turn)]
    (api/invoke! c {:op :turn/steer :args {:thread-id (::thread/id reference)
                                           :expected-turn-id (::id reference) :input (input/normalize input)}})))
(defn interrupt! "Request interruption. Completion arrives through events." [c turn]
  (let [r (ref turn)] (api/invoke! c {:op :turn/interrupt :args {:thread-id (::thread/id r) :turn-id (::id r)}})))
