(ns songpark.jam.platform
  (:require [com.stuartsierra.component :as component]
            [songpark.jam.platform.protocol :as proto]
            [songpark.jam.util :refer [get-id
                                       get-jam-topic
                                       get-jam-topic-subscriptions]]
            [songpark.mqtt :as mqtt :refer [handle-message]]
            [songpark.mqtt.util :refer [teleporter-topic]]
            [taoensso.timbre :as log]
            [tick.core :as t]))

(defprotocol IJamPlatform
  (start [platform tp-ids] "Start a jam with the teleporters")
  (stop [platform jam-id] "Stop the jam")
  (joined [platform jam-id teleporter-id])
  (left [platform jam-id teleporter-id])
  (check-for-timeouts [platform] "Check if any jams have timed out after trying to stop them"))

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

(defn get-jam-member [db jam-id tp-id]
  (let [{:keys [jam/members]} (proto/read-db db [:jams jam-id])]
    (->> members
         (filter #(= tp-id (:teleporter/id %)))
         first)))

(defn write-jam-member [db jam-id teleporter-id k v]
  (let [{:keys [jam/members]} (proto/read-db db [:jams jam-id])
        idx (reduce (fn [idx {:keys [teleporter/id]}]
                      (if (= id teleporter-id)
                        (reduced idx)
                        (inc idx)))
                    0 members)]
    (when idx
      (let [member (get members idx)]
        (proto/write-db db [:jams jam-id :jam/members] (assoc members idx (assoc member k v)))))))

(defrecord MemDB [kv-map]
  proto/IJamDB
  (read-db [_ key-path] (get-in @kv-map key-path))
  (write-db [_ key-path value] (swap! kv-map assoc-in key-path value))
  (delete-db [_ key-path] (swap! kv-map dissoc-in key-path)))

(defn mem-db
  ([]
   (map->MemDB {:kv-map (atom {})}))
  ([data]
   (map->MemDB {:kv-map (atom data)})))

(defn- setup-jam! [db mqtt-client tp-ids]
  (let [jam-id (get-id)
        teleporters (proto/read-db db [:teleporter])
        members (as-> teleporters $
                  (select-keys $ tp-ids)
                  (vals $)
                  (map #(select-keys % [:teleporter/id :teleporter/ip]) $)
                  (sort-by :teleporter/id $)
                  (into [] $))
        jam {:jam/id jam-id
             :jam/members members
             :jam/status :jam/start}]
    (proto/write-db db [:jams jam-id] jam)
    (let [msg (-> jam
                  (select-keys [:jam/id :jam/members])
                  (assoc :message/type :jam.cmd/join))]
      (doseq [{:keys [teleporter/id]} members]
        (mqtt/publish mqtt-client id msg)))))

(defn- start* [{:keys [db mqtt-client]} tp-ids]
  (let [jams (proto/read-db db [:jams])
        tp-ids (set tp-ids)
        jamming? (->> jams
                      (filter (fn [[jam-id {:keys [jam/members]}]]
                                (= (set (map :teleporter/id members)) tp-ids)))
                      first)]
    (if jamming?
      (throw (ex-info "At least one of the teleporters are already jamming" {:tp-ids tp-ids}))
      (setup-jam! db mqtt-client tp-ids))))

(defn- stop* [{:keys [db mqtt-client]} jam-id]
  (let [{:keys [jam/members]} (proto/read-db db [:jams jam-id])
        msg {:message/type :jam.cmd/stop
             :jam/id jam-id}
        topic (get-jam-topic :jam jam-id)]
    (doseq [{:keys [teleporter/id]} members]
      (mqtt/publish mqtt-client id msg))
    (mqtt/publish mqtt-client topic msg)
    (proto/write-db db [:jams jam-id :jam/status] :stopping)
    (proto/write-db db [:jams jam-id :jam/timeout] (t/now))))

(defn- joined* [{:keys [db mqtt-client]} jam-id teleporter-id]
  (let [member (get-jam-member db jam-id teleporter-id)]
    (when member
      (write-jam-member db jam-id teleporter-id :jam/joined? true))
    (let [{:keys [jam/members]} (proto/read-db db [:jams jam-id])]
      (when (every? :jam/joined? members)
        (proto/write-db db [:jams jam-id :jam/status] :jam/running)
        (doseq [{:keys [teleporter/id]} members]
          (mqtt/publish mqtt-client id {:message/type :jam.cmd/start
                                        :teleporter/id id
                                        :jam/id jam-id}))))))

(defn- left* [{:keys [db]} jam-id teleporter-id]
  (let [member (get-jam-member db jam-id teleporter-id)]
    (when member
      (write-jam-member db jam-id teleporter-id :jam/left? true))
    (let [{:keys [jam/members]} (proto/read-db db [:jams jam-id])]
      (when (every? :jam/left? members)
        (proto/delete-db db [:jams jam-id])))))

(defn- check-for-timeouts* [{:keys [db mqtt-client timeout-ms-jam-eol]}]
  (let [now (t/now)
        jams-eol (->> (proto/read-db db [:jams])
                      (filter (fn [[_ {:jam/keys [status timeout]}]]
                                (and (= :stopping status)
                                     (t/> now (t/>> timeout (t/new-duration timeout-ms-jam-eol :millis))))))
                      (map (fn [[jam-id jam]]
                             (assoc jam :jam/id jam-id))))]
    (doseq [{:jam/keys [members id]} jams-eol]
      (doseq [{tp-id :teleporter/id} members]
        (mqtt/publish mqtt-client tp-id {:message/type :jam.cmd/reset
                                         :teleporter/id tp-id}))
      ;; delete jam
      (proto/delete-db db [:jams id]))))

(defrecord JamManager [started? db mqtt-client timeout-ms-waiting timeout-ms-jam-eol]
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
  (start [this tp-ids]
    (start* this tp-ids))
  (stop [this jam-id]
    (stop* this jam-id))
  (joined [this jam-id teleporter-id]
    (joined* this jam-id teleporter-id))
  (left [this jam-id teleporter-id]
    (left* this jam-id teleporter-id))
  (check-for-timeouts [this]
    (check-for-timeouts* this)))

(defn jam-manager [settings]
  (map->JamManager (merge {:db (mem-db)
                           ;; 15 seconds
                           :timeout-ms-jam-eol (* 15 1000)}
                          settings)))
