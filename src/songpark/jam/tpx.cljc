(ns songpark.jam.tpx
  (:require [clojure.core.async :as async]
            [com.stuartsierra.component :as component]
            [songpark.mqtt :as mqtt]
            [songpark.jam.tpx.ipc :as tpx.ipc]
            [songpark.jam.util :as jam.util]
            [taoensso.timbre :as log]))

(declare set-state)

(defprotocol IJamTPX
  (join [component jam-data])
  (leave [component])
  (get-state [component]))

(defn- update-jam-teleporter [jam tpid what status]
  (swap! (:data jam) assoc-in [:jam/teleporters tpid what] status))
(defn- clear-jam-teleporters [jam]
  (swap! (:data jam) assoc :jam/teleporters {}))

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
                   [nil []])
        sips-in-order (map #(get sips %) join-order)]
    sips))

(defn- jam-join [ipc join-order]
  (log/debug :jam-join {:join-order join-order})
  (doseq [sip join-order]
    (tpx.ipc/command ipc :sip/call sip)))

(defn- jam-leave [ipc leave-order]
  (log/debug :jam-leave {:leave-order leave-order})
  (tpx.ipc/command ipc :jam/stop-coredump true)
  (doseq [sip leave-order]
    (tpx.ipc/command ipc :sip/hangup sip)))


(defn- join* [{:keys [tp-id data mqtt-client ipc] :as jam} jam-data]
  (reset! data (select-keys jam-data [:jam/sip :jam/members :jam/id]))
  (set-state jam :jam/joined)
  (let [topics (jam.util/get-jam-topic-subscriptions :platform jam-data)
        join-order (get-call-order tp-id (:jam/members jam-data) (:jam/sip jam-data))]
    (mqtt/subscribe mqtt-client topics)
    (jam-join ipc join-order)))

(defn- leave* [{:keys [data tp-id mqtt-client ipc] :as jam}]
  (let [jam-data @data
        topic (jam.util/get-jam-topic :jam jam-data)
        topics (jam.util/get-jam-topic-subscriptions :platform jam-data)
        leave-order (get-call-order tp-id (:jam/members jam-data) (:jam/sip jam-data))]
    (mqtt/publish mqtt-client topic {:message/type :jam.teleporter/leaving
                                     :teleporter/id tp-id})
    (jam-leave ipc leave-order)
    (mqtt/unsubscribe mqtt-client (keys topics))
    (set-state jam :idle)))

(defn- get-state* [jam]
  (-> jam :data deref :state))

(defn- set-state [{:keys [data] :as _jam} value]
  (swap! data assoc :state value))

(defn- jam-status
  ([{:keys [data] :as _jam}]
   (:jam/status @data))
  ([{:keys [data] :as _jam} status]
   (swap! data assoc :jam/status status)))

(defn idle? [jam]
  (= :idle (get-state jam)))

(defn jamming? [jam]
  (#{:sip/making-call
     :sip/call
     :sip/in-call
     :sip/calling
     :sip/incoming-call
     :sip/hangup
     :stream/syncing
     :stream/sync-failed
     :stream/streaming
     :stream/stopped} (get-state jam)))

(defn- broadcast-to-jam [{:keys [mqtt-client data] :as _jam} data]
  (let [jam-data @data
        topic (jam.util/get-jam-topic :teleporters/app jam-data)]
    (mqtt/publish mqtt-client topic jam-data)))

(defn- get-other-teleporter-id [{:keys [tp-id data] :as _jam}]
  (as-> data $
    (deref $)
    (:jam/members $)
    (into #{} $)
    (disj $ tp-id)
    (first $)))

(defn- broadcast-jam-status [{:keys [data tp-id] :as jam}]
  (if (jamming? jam)
    (broadcast-to-jam jam (merge (select-keys @data [:jam/teleporters])
                                 {:message/type :jam.teleporter/status
                                  :teleporter/id tp-id}))
    (log/error "Trying to broadcast to a jam when not in a jam")))

(defn- handle-ipc-value [{:keys [data tp-id mqtt-client] :as jam}
                         {:keys [event/type event/value] :as v}]
  (log/debug :handle-ipc-value v)
  (case type
    ;; volumes are messed up because of how the CLI interface
    ;; of the bridge program works. You are always printed a table
    ;; with all the values, regardless of what you asked for. As such,
    ;; you will have race conditions where you cannot know which is which,
    ;; and therefore it's better to just send all the volumes
    :volume/global-volume
    (broadcast-to-jam jam {:message/type :teleporter/volumes
                           :teleporter/volumes value
                           :teleporter/id tp-id})
    :volume/local-volume
    (broadcast-to-jam jam {:message/type :teleporter/volumes
                           :teleporter/volumes value
                           :teleporter/id tp-id})
    :volume/network-volume
    (broadcast-to-jam jam {:message/type :teleporter/volumes
                           :teleporter/volumes value
                           :teleporter/id tp-id})
    :jam/playout-delay
    (broadcast-jam-status jam)

    :sip/making-call
    (do
      (set-state jam type)
      (if (jamming? jam)
        (let [other-tp-id (get-other-teleporter-id jam)]
          (update-jam-teleporter jam other-tp-id :sip type)
          (broadcast-jam-status jam))))
    :sip/calling
    (do
      (set-state jam type)
      (if (jamming? jam)
        (let [other-tp-id (get-other-teleporter-id jam)]
          (update-jam-teleporter jam other-tp-id :sip type)
          (broadcast-jam-status jam))))
    :sip/incoming-call
    (do
      (set-state jam type)
      (if (jamming? jam)
        (let [other-tp-id (get-other-teleporter-id jam)]
          (update-jam-teleporter jam other-tp-id :sip type)
          (broadcast-jam-status jam))))

    :sip/in-call
    (do
      (set-state jam type)
      (if (jamming? jam)
        (let [other-tp-id (get-other-teleporter-id jam)]
          (update-jam-teleporter jam other-tp-id :sip type)
          (broadcast-jam-status jam))))
    :stream/syncing
    (do
      (set-state jam type)
      (if (jamming? jam)
        (let [other-tp-id (get-other-teleporter-id jam)]
          (update-jam-teleporter jam other-tp-id :stream type)
          (broadcast-jam-status jam))))
    :stream/sync-failed
    (do
      (set-state jam type)
      (if (jamming? jam)
        (let [other-tp-id (get-other-teleporter-id jam)]
          (update-jam-teleporter jam other-tp-id :stream type)
          (broadcast-jam-status jam))))
    :stream/streaming
    (do
      (set-state jam type)
      (if (jamming? jam)
        (let [other-tp-id (get-other-teleporter-id jam)]
          (update-jam-teleporter jam other-tp-id :stream type)
          (broadcast-jam-status jam))))
    :stream/stopped
    (do
      (set-state jam type)
      (if (jamming? jam)
        (let [other-tp-id (get-other-teleporter-id jam)]
          (update-jam-teleporter jam other-tp-id :stream type)
          (broadcast-jam-status jam))))
    
    :sip/register
    (mqtt/broadcast mqtt-client {:message/type type
                                 :teleporter/id tp-id})
    
    (log/error "Event type" v "not handled" v)))

(defn- handle-ipc [{:keys [ipc] :as jam}]
  (let [c (:c ipc)
        closer (async/chan)]
    (async/go-loop []
      (let [[v ch] (async/alts! [c closer])]
        (if (identical? closer ch)
          (do (log/debug "Closing closer")
              (log/info "Stepping out of handle-ipc")
              (async/close! closer))

          (do
            (when v
             (log/debug "Handling IPC value" v)
             (try
               (handle-ipc-value jam v)
               (catch Exception e
                 (log/error "Caught exception in handle-ipc" {:exception e
                                                              :v v
                                                              :data (ex-data e)
                                                              :message (ex-message e)}))))
            (recur)))))
    closer))

(defrecord Jam [started? tp-id data mqtt-client ipc closer-chan]
  component/Lifecycle
  (start [this]
    (if started?
      this
      (do (log/info "Starting TPX Jam")
          (let [new-this (assoc this
                                :started? true
                                :data (atom {:state :idle
                                             ;; data about the jam
                                             :#jam {:id #uuid "00000000-0000-0000-0000-000000000000"
                                                    :teleporters {:tp-id-other-or-own {:sip #{:sip/call :sip/in-call :sip/hungup}
                                                                                       :stream #{:stream/syncing :stream/sync-failed :stream/streaming}}}}}))
                closer-chan (handle-ipc new-this)]
            (clear-jam-teleporters new-this)
            (set-state new-this :idle)
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
  (map->Jam settings))
