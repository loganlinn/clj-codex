(ns codex.event
  "Domain notifications and a pure projection of observed conversation state."
  (:require [codex.app-server :as server]
            [codex.impl.schema :as schema]
            [codex.impl.util :as u]))

(def ^:private event-names
  {"item/agentMessage/delta" :item/text-delta
   "item/plan/delta" :item/plan-delta
   "item/commandExecution/outputDelta" :item/command-output
   "turn/plan/updated" :turn/plan-updated "turn/diff/updated" :turn/diff-updated
   "serverRequest/resolved" :interaction/resolved})

(defn normalize "Convert a raw notification/request to a domain envelope. Responses return nil." [conn raw]
  (when-let [method (get raw "method")]
    (let [op (u/operation method)
          request? (contains? raw "id")
          d (get-in @schema/catalog [(if request? :server-requests :notifications) op])
          params (get raw "params")
          data (if-let [s (:args-schema d)] (schema/decode-type s params) {:codex.api/raw params})
          thread-id (or (:thread-id data) (get-in data [:thread :codex.thread/id]))
          turn-id (or (:turn-id data) (get-in data [:turn :codex.turn/id]))
          item-id (or (:item-id data) (get-in data [:item :codex.item/id]))]
      (cond-> {::type (if request? :interaction/requested (get event-names method op))
               ::connection-id (:id conn) ::sequence (:sequence (meta raw))
               ::data data :wire/method method}
        thread-id (assoc :codex.thread/id thread-id)
        turn-id (assoc :codex.turn/id turn-id)
        item-id (assoc :codex.item/id item-id)
        request? (assoc :codex.interaction/id (get raw "id"))))))

(defn matches? "Match an event against optional :thread-id, :turn-id, :item-id, and :types." [filter event]
  (and (or (nil? (:thread-id filter)) (= (:thread-id filter) (:codex.thread/id event)))
       (or (nil? (:turn-id filter)) (= (:turn-id filter) (:codex.turn/id event)))
       (or (nil? (:item-id filter)) (= (:item-id filter) (:codex.item/id event)))
       (or (nil? (:types filter)) (contains? (set (:types filter)) (::type event)))))
(defn listen!
  "Observe matching domain events. :capacity and :on-error configure the local observer."
  ([conn f] (listen! conn {} f))
  ([conn filter f]
   (server/listen! conn (select-keys filter [:capacity :on-error])
                   (fn [raw] (when-let [event (normalize conn raw)]
                               (when (matches? filter event) (f event)))))))
(defn unlisten! "Release an event observer." [subscription] (server/unlisten! subscription))
(defn text-delta "Return an agent text delta, or nil." [event]
  (when (= :item/text-delta (::type event)) (get-in event [::data :delta])))
(defn terminal? "Is this a terminal turn notification?" [event]
  (and (= :turn/completed (::type event))
       (contains? #{:completed :failed :interrupted} (get-in event [::data :turn :codex.turn/status]))))

(defn- remember-item [state key item completed?]
  (let [[t turn i] key]
    (-> state
        (assoc-in [:items key] (cond-> item completed? (assoc :codex.item/completed? true)))
        (update-in [:item-order [t turn]] (fn [ids] (if (some #{i} ids) ids (conj (or ids []) i)))))))

(defn apply-event
  "Pure reducer. State contains :threads, :turns, :items, :item-order, and :pending maps."
  [state event]
  (let [t (:codex.thread/id event) turn (:codex.turn/id event) item (:codex.item/id event)
        data (::data event) type (::type event) key [t turn item]]
    (case type
      :thread/started (update-in state [:threads t] merge (:thread data))
      :thread.status/changed (assoc-in state [:threads t :codex.thread/status] (:status data))
      :thread.name/updated (assoc-in state [:threads t :codex.thread/name] (:thread-name data))
      (:thread/archived :thread/unarchived :thread/deleted :thread/closed)
      (assoc-in state [:threads t ::lifecycle] type)
      (:turn/started :turn/completed)
      (let [snapshot (:turn data)
            state (update-in state [:turns [t turn]] merge (dissoc snapshot :codex.turn/items))]
        (reduce (fn [s value]
                  (let [k [t turn (:codex.item/id value)]]
                    (if (get-in s [:items k :codex.item/completed?]) s
                        (remember-item s k value (= :turn/completed type)))))
                state (:codex.turn/items snapshot)))
      :item/started (if (get-in state [:items key :codex.item/completed?]) state
                        (remember-item state key (:item data) false))
      :item/completed (remember-item state key (:item data) true)
      (:item/text-delta :item/plan-delta :item/command-output)
      (if (get-in state [:items key :codex.item/completed?]) state
          (let [field (if (= type :item/command-output) :codex.item/aggregated-output :codex.item/text)
                current (get-in state [:items key] {:codex.item/id item
                                                    :codex.item/type (case type :item/text-delta :agent-message
                                                                           :item/plan-delta :plan :command-execution)})]
            (remember-item state key (update current field (fnil str "") (:delta data)) false)))
      :turn/plan-updated (assoc-in state [:turns [t turn] :codex.turn/plan] data)
      :turn/diff-updated (assoc-in state [:turns [t turn] :codex.turn/diff] (:diff data))
      :thread.token-usage/updated (assoc-in state [:threads t :codex.thread/token-usage] (:token-usage data))
      :thread.goal/updated (assoc-in state [:threads t :codex.goal/goal] (:goal data))
      :thread.goal/cleared (update-in state [:threads t] dissoc :codex.goal/goal)
      :interaction/requested (assoc-in state [:pending (:codex.interaction/id event)] event)
      :interaction/resolved (update state :pending dissoc (:request-id data))
      state)))

(defn turn-snapshot "Read a turn projection, with items in observed order." [state thread-id turn-id]
  (assoc (get-in state [:turns [thread-id turn-id]] {:codex.turn/id turn-id})
         :codex.thread/id thread-id
         :codex.turn/items (mapv #(get-in state [:items [thread-id turn-id %]])
                                 (get-in state [:item-order [thread-id turn-id]] []))))
