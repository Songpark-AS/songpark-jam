(ns songpark.jam.tpx
  (:require [clojure.core.async :as async]
            [com.stuartsierra.component :as component]
            [songpark.mqtt :as mqtt]
            [songpark.jam.tpx.ipc :as tpx.ipc]
            [songpark.jam.util :as jam.util]
            [taoensso.timbre :as log]))

(defprotocol IJamTPX
  (join [component jam-data])
  (leave [component])
  (get-state [component]))

(defn- get-call-order [tp-id join-order sips]
  (let [indexed-join-order (map vector join-order (range))
        starting-position (reduce (fn [_ [id idx]]
                                    (if (= tp-id id)
                                      (reduced idx)
                                      nil))
                                  nil indexed-join-order)
        sips-in-order (map #(get sips %) join-order)
        [_ sips] (if-not (empty? sips-in-order)
                   (split-at (inc starting-position) sips-in-order)
                   [nil []])]
    sips))

(defn- jam-join [tp-id ipc join-order]
  (log/debug :jam-join {:join-order join-order})
  (doseq [sip join-order]
    (tpx.ipc/command ipc :sip/call sip)))

(defn- jam-leave [tp-id ipc leave-order]
  (log/debug :jam-leave {:leave-order leave-order})
  (doseq [sip leave-order]
    (tpx.ipc/command ipc :sip/hangup sip)))


(defn- join* [{:keys [tp-id data mqtt-client ipc] :as _jam} jam-data]
  (swap! data assoc :jam/data jam-data)
  (let [topics (jam.util/get-jam-topic-subscriptions jam-data)
        join-order (get-call-order tp-id (:jam/members jam-data) (:jam/sip jam-data))]
    (mqtt/subscribe mqtt-client topics)
    (jam-join tp-id ipc join-order)))

(defn- leave* [{:keys [data tp-id mqtt-client ipc] :as _jam}]
  (let [jam-data (:jam/data @data)
        topic (jam.util/get-jam-topic-jam jam-data)
        topics (jam.util/get-jam-topic-subscriptions jam-data)
        leave-order (get-call-order tp-id (:jam/members jam-data) (:jam/sip jam-data))]
    (mqtt/publish mqtt-client topic {:message/type :jam.teleporter/leaving
                                     :teleporter/id tp-id})
    (jam-leave tp-id ipc leave-order)
    (mqtt/unsubscribe mqtt-client (keys topics))))

(defn- get-state* [jam]
  (-> jam :data deref :state))

(defn- set-state [{:keys [data] :as _jam} value]
  (swap! data assoc :state value))

(defn- idle? [jam]
  (= :idle (get-state jam)))

(defn- jamming? [jam]
  (= :jamming (get-state jam)))

(defn- update-saved-values [{:keys [saved-values] :as _jam} what value]
  (swap! saved-values assoc what value))

(defn- broadcast-to-jam [{:keys [mqtt-client data] :as _jam} data]
  (let [topic (jam.util/get-jam-topic-jam (:jam/data @data))]
    (mqtt/publish mqtt-client topic data)))

(defn- handle-ipc-value [{:keys [data tp-id] :as jam}
                         {:keys [event/type event/value] :as v}]
  (log/debug :handle-ipc-value v)
  (case type
    :volume/global-volume (do
                            (update-saved-values jam type value)
                            (if (jamming? jam)
                              (broadcast-to-jam jam {:message/type type
                                                     type value
                                                     :teleporter/id tp-id})))
    :volume/local-volume (do
                           (update-saved-values jam type value)
                           (if (jamming? jam)
                             (broadcast-to-jam jam {:message/type type
                                                    type value
                                                    :teleporter/id tp-id})))
    :volume/network-volume (do
                             (update-saved-values jam type value)
                             (if (jamming? jam)
                               (broadcast-to-jam jam {:message/type type
                                                      type value
                                                      :teleporter/id tp-id})))
    :jam/playout-delay (do
                         (update-saved-values jam type value)
                         (if (jamming? jam)
                           (broadcast-to-jam jam {:message/type type
                                                  type value
                                                  :teleporter/id tp-id})))
    (log/error "Event type" v "not handled" v)))

(defn- handle-ipc [{:keys [ipc] :as jam}]
  (let [c (:c ipc)
        closer (async/chan)]
    (async/go-loop []
      (let [[v ch] (async/alts! [c closer])]
        (if (identical? closer ch)
          (do (log/debug "Closing closer")
              (async/close! closer))

          (when v
            (do (log/debug "Handling IPC value" v)
                (handle-ipc-value jam v))))
        (recur)))))

(defrecord Jam [started? tp-id data mqtt-client ipc saved-values closer-chan]
  component/Lifecycle
  (start [this]
    (if started?
      this
      (do (log/info "Starting TPX Jam")
          (let [new-this (assoc this
                                :started? true
                                :data (atom {:state :idle
                                             ;; data about the jam
                                             :jam/data {:jam/id #uuid "00000000-0000-0000-0000-000000000000"}
                                             
                                             ;; -1 is id of teleporter
                                             ;; self/status is used for ingoing call/stream status
                                             :self/status {:call {-1 #{:ringing :in-call :hungup}}
                                                           :stream #{:connecting :syncing :streaming}}
                                             ;; -1 is id of teleporter. used for outgoing status
                                             :jam/teleporters {-1 {:call-status #{:ringing :in-call :hungup}
                                                                   :stream-status #{:connecting :syncing :streaming}}}}))
                closer-chan (handle-ipc new-this)]
            (assoc new-this
                   :closer-chan closer-chan)))))
  (stop [this]
    (if-not started?
      this
      (do (log/info "Stopping TPX Jam")
          (async/put! closer-chan true)
          (assoc this
                 :started? false
                 :data (atom {})))))
  IJamTPX
  (join [this jam-data]
    (join* this jam-data))
  (leave [this]
    (leave* this))
  (get-state [this]
    (get-state* this)))

(defn get-jam [settings]
  (map->Jam (merge {:saved-values (atom {})}
                   settings)))
