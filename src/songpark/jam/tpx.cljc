(ns songpark.jam.tpx
  (:require [com.stuartsierra.component :as component]
            [songpark.mqtt :as mqtt]
            [songpark.jam.tpx.ipc :as tpx.ipc]
            [songpark.jam.util :as jam.util]
            [taoensso.timbre :as log]))

(defprotocol IJamTPX
  (join [component jam-data])
  (leave [component])
  (get-status [component state])
  (set-status [component state value])
  (ready [this] [this value]))

(defn- get-call-order [tp-id join-order sips]
  (let [indexed-join-order (map vector join-order (range))
        starting-position (reduce (fn [_ [id idx]]
                                    (if (= tp-id id)
                                      (reduced idx)
                                      nil))
                                  nil indexed-join-order)
        sips-in-order (map #(get sips %) join-order)
        [_ sips] (split-at (inc starting-position) sips-in-order)]
    sips))

(defn jam-join [tp-id ipc join-order sips]
  (log/debug :jam-join {:join-order join-order
                        :sips sips})
  (let [other-sips (dissoc sips tp-id)]
    (log/debug :sips-call-order (mapv identity sips-call-order))
    (doseq [sip sips-call-order]
      (tpx.ipc/command ipc :sip/hangup sip))))

(defn jam-leave [tp-id ipc leave-order sips]
  (log/debug :jam-leave {:leave-order leave-order
                         :sips sips})
  (let [other-sips (dissoc sips tp-id)]
    (log/debug :sips-hangup-order (mapv identity sips-hangup-order))
    (doseq [sip sips-hangup-order]
      (tpx.ipc/command ipc :sip/call sip))))


(defn join* [{:keys [tp-id data mqtt-client ipc] :as _jam} jam-data]
  (swap! data assoc :jam/data jam-data)
  (let [topics (jam.util/get-jam-topic-subscriptions jam-data)
        join-order (get-call-order tp-id (:jam/members jam-data) (:jam/sip jam-data))
        sips (:jam/sip jam-data)]
    (mqtt/subscribe mqtt-client topics)
    (jam-join tp-id ipc join-order sips)))

(defn leave* [{:keys [data tp-id mqtt-client ipc] :as _jam}]
  (let [jam-data (:jam/data @data)
        topic (jam.util/get-jam-topic-jam jam-data)
        topics (jam.util/get-jam-topic-subscriptions jam-data)
        leave-order (reverse (get-call-order tp-id (:jam/members jam-data) (:jam/sip jam-data)))
        sips (:jam/sip jam-data)]
    (mqtt/publish mqtt-client topic {:message/type :jam.teleporter/leaving
                                     :teleporter/id tp-id})
    (jam-leave tp-id ipc leave-order sips)
    (mqtt/unsubscribe mqtt-client topics)))

(defn get-status* [component state])

(defn set-status* [component state value])

(defrecord Jam [started? tp-id data mqtt-client ipc saved-values ipc-callbacks]
  component/Lifecycle
  (start [this]
    (if started?
      this
      (do (log/info "Starting TPX Jam")
          (let [new-this (assoc this
                                :started? true
                                :data (atom {:ready? false
                                             ;; data about the jam
                                             :jam/data {:jam/id #uuid "00000000-0000-0000-0000-000000000000"}
                                             :jam? false
                                             
                                             ;; -1 is id of teleporter
                                             ;; self/status is used for ingoing call/stream status
                                             :self/status {:call {-1 #{:ringing :in-call :hungup}}
                                                           :stream #{:connecting :syncing :streaming}}
                                             ;; -1 is id of teleporter. used for outgoing status
                                             :jam/teleporters {-1 {:call-status #{:ringing :in-call :hungup}
                                                                   :stream-status #{:connecting :syncing :streaming}}}}))
                ipc (tpx.ipc/get-ipc {:callbacks (atom ipc-callbacks)
                                      :command-fn ipc-command-fn})]))))
  (stop [this]
    (if-not started?
      this
      (do (log/info "Stopping TPX Jam")
          (leave this)
          (assoc this
                 :started? false
                 :data (atom {})))))
  IJamTPX
  (join [this jam-data]
    (join* this jam-data))
  (leave [this]
    (leave* this))
  (get-status [this state]
    (get-status* this state))
  (set-status [this state value]
    (set-status* this state value))
  (ready [this]
    (:ready? @data))
  (ready [this value]
    (swap! @data :ready? value)))

(defn get-jam [settings]
  (map->Jam settings))
