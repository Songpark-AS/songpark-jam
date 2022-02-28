(ns songpark.jam.platform
  (:require [com.stuartsierra.component :as component]
            [songpark.jam.platform.protocol :as proto]
            [songpark.jam.util :refer [get-id
                                       get-jam-topic]]
            [songpark.mqtt :as mqtt]
            [songpark.mqtt.util :refer [teleporter-topic]]
            [taoensso.timbre :as log]
            [tick.core :as t]))

(defprotocol IJamPlatform
  (ask [platform tp-id] "Ask for a jam")
  (obviate [platform tp-id] "The TP no longer want to participate in a jam")
  (stop [platform jam-id] "Stop the jam")
  (check-for-timeouts [platform] "Check if any TPs in the waiting list have timed out"))

(defn dissoc-in
  "Dissociate a value in a nested assocative structure, identified by a sequence
  of keys. Any collections left empty by the operation will be dissociated from
  their containing structures."
  [m ks]
  (if-let [[k & ks] (seq ks)]
    (if (seq ks)
      (let [v (dissoc-in (get m k) ks)]
        (if (empty? v)
          (dissoc m k)
          (assoc m k v)))
      (dissoc m k))
    m))

(defrecord MemDB [kv-map]
  proto/IJamDB
  (read-db [_ key-path] (get-in @kv-map key-path))
  (write-db [_ key-path value] (swap! kv-map assoc-in key-path value))
  (delete-db [_ key-path] (swap! kv-map dissoc-in key-path)))

(defn mem-db []
  (map->MemDB {:kv-map (atom {})}))

(defn- get-sips [teleporters members]
  (let [members (set members)]
    (->> teleporters
         (vals)
         (filter #(members (:teleporter/id %)))
         (map (juxt :teleporter/id  :teleporter/sip))
         (into {}))))

(defn- get-start-order [members]
  (let [members (set members)
        zedboard1 #uuid "f7a21b06-014d-5444-88d7-0374a661d2de"]
    ;; for debugging and development purposes during the final sprint to get
    ;; a working prototype. we always want "zedboard-01" to be first, since
    ;; we are developing on that one via the REPL
    (if (members zedboard1)
      (into [zedboard1] (disj members zedboard1))
      (into [] members))))

(defn- setup-jam! [db waiting mqtt-client tp-id-1]
  ;; waiting should only ever have 0 or 1 entry
  (let [[tp-id-2 _] (first waiting)
        jam-id (get-id)
        members (get-start-order [tp-id-1 tp-id-2])
        teleporters (proto/read-db db [:teleporters])
        jam {:jam/id jam-id
             :jam/sip (get-sips teleporters members)
             :jam/members members}]
    (proto/write-db db [:jam jam-id] jam)
    (proto/delete-db db [:teleporter tp-id-1 :teleporter/status])
    (proto/delete-db db [:teleporter tp-id-2 :teleporter/status])
    (proto/delete-db db [:waiting tp-id-1])
    (proto/delete-db db [:waiting tp-id-2])
    (let [msg (assoc jam :message/type :jam.cmd/start)]
      (doseq [id members]
        (let [topic (teleporter-topic id)]
          (mqtt/publish mqtt-client topic msg))))
    (mqtt/publish mqtt-client "jam" (assoc jam :message/type :jam/info))))

(defn- ask* [{:keys [db mqtt-client]} tp-id]
  (let [jams (proto/read-db db [:jams])
        waiting (proto/read-db db [:waiting])
        jamming? (->> jams
                      (filter (fn [[jam-id {:keys [jam/members]}]]
                                ((set members) tp-id)))
                      first)
        can-jam? (and (not (contains? waiting tp-id))
                      (pos? (count waiting)))]
    (cond
      ;; do nothing
      jamming?
      nil
      can-jam?
      (setup-jam! db waiting mqtt-client tp-id)
      ;; add the tp-id to the waiting list and send back a reply over mqtt that it's waiting
      :else
      (do (proto/write-db db [:waiting tp-id] (t/now))
          (proto/write-db db [:teleporter tp-id :teleporter/status] :waiting)
          (mqtt/publish mqtt-client "jam" {:message/type :jam/waiting
                                           :teleporter/id tp-id})))))

(defn- stop* [{:keys [db mqtt-client]} jam-id]
  (let [{:keys [jam/members]} (proto/read-db db [:jam jam-id])
        msg {:message/type :jam.cmd/stop
             :jam/id jam-id}
        topic (get-jam-topic :jam jam-id)]
    (mqtt/publish mqtt-client topic msg)
    (proto/delete-db db [:jam jam-id])
    (mqtt/publish mqtt-client "jam" {:message/type :jam/stopped
                                     :jam/id jam-id})))

(defn- obviate* [{:keys [db mqtt-client]} tp-id]
  (proto/delete-db db [:waiting tp-id])
  (mqtt/publish mqtt-client "jam" {:message/type :jam/obviated
                                   :teleporter/id tp-id}))

(defn- check-for-timeouts* [{:keys [db mqtt-client timeout-ms]}]
  (println timeout-ms)
  (let [waiting (proto/read-db db [:waiting])
        now (t/now)
        timed-out (->> waiting
                       (filter (fn [[tp-id timeout]]
                                 (t/> now (t/>> timeout (t/new-duration timeout-ms :millis)))))
                       (map first))]
    (when-not (empty? timed-out)
      (doseq [tp-id timed-out]
        (mqtt/publish mqtt-client "jam" {:message/type :jam/ask-timed-out
                                         :teleporter/id tp-id})
        (proto/delete-db db [:waiting tp-id])))))

(defrecord JamManager [started? db mqtt-client timeout-ms]
  component/Lifecycle
  (start [this]
    (if started?
      this
      (do (log/info "Starting JamManager")
          (assoc this
                 :started? true))))
  (stop [this]
    (if-not started?
      this
      (do (log/info "Stopping JamMananger")
          (assoc this
                 :started? false))))
  IJamPlatform
  (ask [this tp-id]
    (ask* this tp-id))
  (stop [this jam-id]
    (stop* this jam-id))
  (obviate [this tp-id]
    (obviate* this tp-id))
  (check-for-timeouts [this]
    (check-for-timeouts* this)))

(defn jam-manager [settings]
  (map->JamManager (merge {:db (mem-db)
                           ;; 5 minutes
                           :timeout-ms (* 5 60 1000)}
                          settings)))
