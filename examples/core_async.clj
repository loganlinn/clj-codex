#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(ns examples.core-async
  "Observe a local session's persisted events with core.async mult and tap.

  bb examples/core_async.clj SESSION_ID
  bb examples/core_async.clj SESSION_ID --from-now
  bb examples/core_async.clj SESSION_ID --once
  bb examples/core_async.clj SESSION_ID --once --all --format jsonl

  SESSION_ID is the thread ID from review_agent.clj or thread/start!.
  thread/read! locates the log in the current Codex home, including CODEX_HOME.
  The lookup server closes before replay starts. Reading the log neither resumes
  the session nor starts a model turn. Ephemeral sessions have no persisted log.

  By default, print event_msg records as EDN, with timestamps and string JSON
  keys. --all includes metadata, context, and response records. These records
  differ from codex.event/listen! events and can omit live token deltas.
  Events appear after Codex writes them to disk. The log path and format are
  internal Codex details that can change between versions.

  Replay follows new records until Ctrl-C, including after turn completion.
  --from-now skips existing records and any record in progress at attachment.
  --once stops at the initial file size and omits an incomplete final record.
  The reader polls every 250 ms and decodes only complete UTF-8 JSONL lines.

  A mult broadcasts to two taps: a printer and a counter. Both receive every
  selected record in order. The counter reports its total to stderr at the end.
  Both taps attach before the producer starts because a mult has no replay.
  A full tap buffer (32 records) pauses delivery until its consumer catches up.
  Blocking file and output operations run in async/thread. The producer closes
  its channel on completion or error. The mult closes both taps after delivery.
  Worker errors propagate to the CLI. Babashka includes core.async.

  To attach your own consumers in a Babashka REPL:
    (load-file \"examples/core_async.clj\")
    (in-ns 'examples.core-async)
    (def path (session-path! \"SESSION_ID\"))
    (def events (async/chan 32))
    (def broadcast (async/mult events))
    (def latest (async/tap broadcast (async/chan (async/sliding-buffer 1))))
    (def counted (async/tap broadcast (async/chan 32)))
    (def total (async/reduce (fn [n _] (inc n)) 0 counted))
    (def reader
      (async/thread
        (try
          (stream-log! path events {:once true})
          {:ok true}
          (catch Exception e {:error e}))))
    (async/<!! reader)
    (async/<!! total)
    (async/<!! latest)

  The sliding buffer retains the latest record while the counter receives all.
  For continuous observation, pass a :stop channel to stream-log! and omit :once.
  Closing :stop also releases a producer blocked by a slow tap. Consumers must
  drain their channels so the mult can finish delivery. To detach a consumer,
  call async/untap, then close and drain its channel to release in-flight delivery.

  Reference: https://clojure.github.io/core.async/clojure.core.async.html#var-mult"
  (:use examples.support)
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [codex.app-server :as server]
            [codex.thread :as thread])
  (:import [java.io ByteArrayOutputStream RandomAccessFile]))

(defn session-path! [id]
  (server/with-connection [c (connect! {})]
    ;; Reading locates the log without loading or subscribing to the thread.
    (or (::thread/path (thread/read! c id))
        (throw (ex-info "This session has no local log" {:id id})))))

(defn follow-log!
  "Pass complete UTF-8 JSONL records to consume! without retaining history.
   :once reads through the initial file size. :from-now skips existing records.
   Stop when :stop? returns true or consume! returns a reduced value."
  [path {:keys [once from-now poll-ms stop?] :or {poll-ms 250 stop? (constantly false)}} consume!]
  (with-open [file (RandomAccessFile. (str path) "r")
              line (ByteArrayOutputStream.)]
    (let [end (.length file)
          buffer (byte-array 8192)
          ;; If attachment lands inside a record, discard its remaining bytes.
          skip? (when (and from-now (pos? end))
                  (.seek file (dec end))
                  (not= 10 (.read file)))]
      (loop [skip? skip?]
        (when-not (stop?)
          (when (< (.length file) (.getFilePointer file))
            (throw (ex-info "Session log was truncated. Restart the tap." {:path (str path)})))
          (let [size (if once (int (min (alength buffer) (- end (.getFilePointer file)))) (alength buffer))
                n (if (pos? size) (.read file buffer 0 size) -1)]
            (if (pos? n)
              (let [next-skip?
                    (reduce (fn [skip? i]
                              (let [b (bit-and 0xff (aget buffer i))]
                                (if (= 10 b)
                                  (let [record (.toString line "UTF-8")
                                        result (when-not (or skip? (str/blank? record)) (consume! record))]
                                    (.reset line)
                                    (if (reduced? result) (reduced ::stopped) false))
                                  (do (when-not skip? (.write line b)) skip?))))
                            skip? (range n))]
                (when-not (= ::stopped next-skip?) (recur next-skip?)))
              (when-not once
                (Thread/sleep poll-ms)
                (recur skip?)))))))))

(defn stream-log!
  "Put parsed records on events, then close it on completion, cancellation, or error.
   This blocks: run it in async/thread. Close the optional :stop channel to cancel."
  [path events {:keys [all stop] :as opts}]
  (try
    (follow-log! path (assoc opts :stop? #(and stop (= stop (second (async/alts!! [stop] :default nil)))))
                 (fn [line]
                   ;; Preserve arbitrary JSON keys in these raw log records.
                   (let [record (json/parse-string line)]
                     (when (or all (= "event_msg" (get record "type")))
                       ;; Cancellation also releases a put blocked by a slow tap.
                       (let [[accepted?] (async/alts!! (if stop [stop [events record]] [[events record]])
                                                       :priority true)]
                         (when-not accepted? (reduced nil)))))))
    (finally (async/close! events))))

(defn tap! [{:keys [id once from-now format] :as opts}]
  (when (str/blank? id) (throw (ex-info "A session ID is required" {})))
  (when (and once from-now)
    (throw (ex-info "Use either --once or --from-now" {})))
  (let [path (session-path! id)]
    (binding [*out* *err*]
      (println (if once "Reading session:" "Tapping session:") id)
      (println "Log:" path)
      (when-not once (println "Waiting for persisted events. Ctrl-C stops the tap.")))
    (let [events (async/chan 32)
          broadcast (async/mult events)
          ;; Attach both taps BEFORE starting the producer: a mult has no replay.
          printed (async/tap broadcast (async/chan 32))
          counted (async/tap broadcast (async/chan 32))
          stop (async/chan)
          total (async/reduce (fn [n _] (inc n)) 0 counted)
          out *out*
          printer (async/thread
                    (try
                      (binding [*out* out]
                        (loop []
                          (when-let [record (async/<!! printed)]
                            (if (= "jsonl" format) (println (json/generate-string record)) (pprint record))
                            (flush)
                            (recur))))
                      {}
                      (catch Exception e
                        (async/close! stop)
                        ;; Drain in-flight broadcasts so the mult can finish.
                        (loop [] (when (async/<!! printed) (recur)))
                        {:error e})))
          producer (async/thread
                     (try
                       (stream-log! path events (assoc opts :stop stop))
                       {}
                       (catch Exception e {:error e})))]
      (try
        ;; The producer closes events; mult closes both taps after delivery.
        (let [printed-result (async/<!! printer)
              read-result (async/<!! producer)
              n (async/<!! total)]
          (when-let [e (or (:error printed-result) (:error read-result))] (throw e))
          (binding [*out* *err*] (println "Observed" n "records.")))
        (finally
          (async/close! stop)
          (doseq [ch [printed counted]]
            (async/untap broadcast ch)
            (async/close! ch)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (main! "bb examples/core_async.clj SESSION_ID [--from-now | --once] [--format edn|jsonl]"
         {:id {:coerce :string :desc "Local Codex session (thread) ID"}
          :from-now {:coerce :boolean :desc "Print only records that start after attachment"}
          :once {:coerce :boolean :desc "Print existing complete records and exit"}
          :all {:coerce :boolean :desc "Include metadata, context, and response records"}
          :poll-ms {:coerce :long :default 250 :validate pos? :desc "Log polling interval in milliseconds"}
          :format {:coerce :string :default "edn" :validate #{"edn" "jsonl"} :desc "Output format"}}
         [:id] tap!))
