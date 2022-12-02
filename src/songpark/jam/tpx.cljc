(ns songpark.jam.tpx
  (:require [clojure.core.async :as async]
            [com.stuartsierra.component :as component]
            [songpark.mqtt :as mqtt]
            [songpark.jam.tpx.ipc :as tpx.ipc]
            [songpark.jam.util :refer [get-jam-topic]]
            [taoensso.timbre :as log]))

(def port 8421)


(defprotocol IJamTPX
  (join [component jam-data] "Join a jam")
  (start-call [component] "Start a call")
  (initiate-call [compontent] "Initiate a call as a caller")
  (receive-call [component] "Wait for a call as a receiver")
  (stop-call [component] "Stop the call")
  (cancel-call [component] "Cancel the jam. For when things have timed out")
  (reset [component] "Reset the TPX to its default state")
  (get-state [component] "Get the current state")
  (set-state [component value] "Set the current state"))

(defn- update-jam-teleporter [tpx tpid what status]
  (swap! (:data tpx) assoc-in [:jam/teleporters tpid what] status))

(defn- clear-jam-teleporters [tpx]
  (swap! (:data tpx) assoc :jam/teleporters {}))

(defn idle?
  "Is the TPX idle?"
  [tpx]
  (= :idle (get-state tpx)))

(defn jamming? [tpx]
  (not (idle? tpx)))

(defn state?
  "Is the TPX in this state?"
  [tpx state]
  (= state (get-state tpx)))

(defn- get-receive-order [{:keys [data tp-id] :as _tpx}]
  (->> (:jam/members @data)
       (drop-while #(not= tp-id (:teleporter/id %)))
       (drop 1)))

(defn- get-initiate-order [{:keys [data tp-id] :as _tpx}]
  (->> (:jam/members @data)
       (take-while #(not= tp-id (:teleporter/id %)))))

(comment

  (get-receive-order {:data (atom {:jam/members [{:teleporter/id 1}
                                                 {:teleporter/id 2}
                                                 {:teleporter/id 3}]})
                      :tp-id 1})
  (get-initiate-order {:data (atom {:jam/members [{:teleporter/id 1}
                                                  {:teleporter/id 2}
                                                  {:teleporter/id 3}]})
                       :tp-id 2})
  )



;; (defn- jam-join [ipc join-order]
;;   (log/debug :jam-join {:join-order join-order})
;;   (doseq [sip join-order]
;;     (tpx.ipc/command ipc :sip/call sip)))

;; (defn- jam-leave [ipc leave-order]
;;   (log/debug :jam-leave {:leave-order leave-order})
;;   (tpx.ipc/command ipc :jam/stop-coredump true)
;;   (doseq [sip leave-order]
;;     (tpx.ipc/command ipc :sip/hangup sip)))

(defn- join* [{:keys [tp-id data mqtt-client ipc] :as tpx} jam-data]
  (if (idle? tpx)
    (do
      (reset! data (select-keys jam-data [:jam/members :jam/id]))
      (set-state tpx :jam/joined)
      (mqtt/publish mqtt-client
                    "platform/request"
                    {:message/type :jam/joined
                     :teleporter/id tp-id
                     :jam/id (:jam/id jam-data)}))
    (mqtt/publish mqtt-client
                  (get-jam-topic (:jam/id jam-data))
                  {:message/type :jam.teleporter/error
                   :teleporter/id tp-id
                   :jam/id (:jam/id jam-data)
                   :teleporter/state (get-state tpx)
                   :error/key :jam.join.error/tpx-not-idle})))

(defn- start-call* [tpx]
  (receive-call tpx))

(defn- initiate-call* [{:keys [data ipc] :as tpx}]
  (let [tps (get-initiate-order tpx)]
    (doseq [tp tps]
      (tpx.ipc/command ipc :call/initiate (assoc tp :teleporter/port port)))))

(defn- receive-call* [{:keys [data ipc mqtt-client tp-id] :as tpx}]
  (let [jam-id (get @data :jam/id)
        tps (get-receive-order tpx)]
    (doseq [{:keys [teleporter/id] :as tp} tps]
      (tpx.ipc/command ipc :call/receive (assoc tp :teleporter/port port))
      (mqtt/publish mqtt-client id {:message/type :jam.call/receive
                                    :jam/id jam-id
                                    :teleporter/id tp-id
                                    :teleporter/port port}))))

(defn- stop-call* [{:keys [data tp-id mqtt-client ipc] :as tpx}]
  (tpx.ipc/command ipc :jam/stop-coredump true)
  (tpx.ipc/command ipc :call/stop true)
  (mqtt/publish mqtt-client "platform/request"
                {:message/type :jam/left
                 :teleporter/id tp-id
                 :jam/id (:jam/id @data)})
  (reset! data nil)
  (set-state tpx :idle))

(defn- reset* [{:keys [tp-id mqtt-client ipc data] :as tpx}]
  (tpx.ipc/command ipc :hangup/all true)
  (mqtt/publish mqtt-client "platform/request"
                {:message/type :teleporter/reset-success
                 :teleporter/id tp-id})
  (reset! data nil)
  (set-state tpx :idle))

(defn- get-state* [jam]
  (-> jam :data deref :state))

(defn- set-state* [{:keys [data] :as _jam} value]
  (swap! data assoc :state value))

(defn- broadcast-to-jam [{:keys [mqtt-client data] :as _jam} msg]
  (let [topic (get-jam-topic (:jam/id @data))]
    (mqtt/publish mqtt-client topic msg)))

(defn- get-other-teleporter-id [{:keys [tp-id data] :as _jam}]
  (as-> data $
    (deref $)
    (:jam/members $)
    (into #{} $)
    (disj $ tp-id)
    (first $)))

(defn- broadcast-jam-status [{:keys [data tp-id] :as tpx} v]
  (let [msg (assoc v
                   :message/type :jam/event
                   :teleporter/id tp-id)]
    (broadcast-to-jam tpx msg)
    (when-not (jamming? tpx)
      (log/error "Trying to broadcast to a jam when not in a jam" msg))))

(defn- handle-ipc-value
  "Handles outgoing IPC values only. Commands are handled seperately"
  ;;  commands are handled in the implementation details of the TPX codebase
  [{:keys [data tp-id mqtt-client] :as jam}
   {:keys [event/type event/value] :as v}]
  (log/debug :handle-ipc-value v)
  (case type
    :sync/syncing
    (broadcast-jam-status jam v)
    :sync/synced
    (broadcast-jam-status jam v)
    :sync/sync-failed
    (broadcast-jam-status jam v)
    :sync/responded
    (broadcast-jam-status jam v)
    :stream/streaming
    (broadcast-jam-status jam v)
    :stream/broken
    (broadcast-jam-status jam v)
    :stream/stopped
    (broadcast-jam-status jam v)
    :jam/coredump
    (broadcast-to-jam jam {:message/type :jam.teleporter/coredump
                           :jam/coredump value
                           :teleporter/id tp-id})

    (log/error "Event type" v "not handled" v)))

(defn- handle-ipc [{:keys [ipc] :as tpx}]
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
                (handle-ipc-value tpx v)
                (catch Exception e
                  (log/error "Caught exception in handle-ipc" {:exception e
                                                               :v v
                                                               :data (ex-data e)
                                                               :message (ex-message e)}))))
            (recur)))))
    closer))

(defrecord TPXJam [started? tp-id data mqtt-client ipc closer-chan]
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
                                                    :teleporters {:tp-id-other-or-own {:teleporter/id #uuid "00000000-0000-0000-0000-000000000000"
                                                                                       :teleporter/local-ip "10.0.0.10"
                                                                                       :teleporter/public-ip "10.100.200.10"
                                                                                       :call #{:call/limbo :call/receiving :call/calling :call/hangup :call/in-call :call/ended}
                                                                                       :stream #{:stream/broken :stream/streaming}
                                                                                       :sync #{:sync/syncing :sync/sync-failed}}}}}))
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
  (start-call [this]
    (start-call* this))
  (initiate-call [this]
    (initiate-call* this))
  (receive-call [this]
    (receive-call* this))
  (stop-call [this]
    (stop-call* this))
  (reset [this]
    (reset* this))
  (get-state [this]
    (get-state* this))
  (set-state [this value]
    (set-state* this value)))

(defn get-jam [settings]
  (map->TPXJam settings))
