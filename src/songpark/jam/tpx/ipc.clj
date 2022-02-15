(ns songpark.jam.tpx.ipc
  (:require [clojure.core.async :as async]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as log]))

(defprotocol IIPC
  (command [this what data])
  (handler [this what]))

(defn command*
  "Used only for testing purposes. This is for manipulating the TPX jam"
  [{:keys [values] :as _ipc} what data]
  (swap! values assoc what data))

(defn handler*
  "Used only for testing purposes. This is for manipulating the TPX jam. Use command first in order to set the desired value"
  [{:keys [values c] :as _ipc} what]
  (Thread/sleep 100)
  (async/put! c {:value (get @values what)
                 :context-map? true
                 :testing? true
                 :what what}))

(defrecord IPC [started? c values command-fn handler-fn]
  component/Lifecycle
  (start [this]
    (if started?
      this
      (do (log/info "Starting IPC tester")
          (assoc this
                 :started? true
                 :c (async/chan (async/sliding-buffer 10))))))
  (stop [this]
    (if-not started?
      this
      (do (log/info "Stopping IPC tester")
          (async/close! c)
          (assoc this
                 :started? false
                 :c nil))))
  IIPC
  (command [this what value]
    (command-fn this what value))
  (handler [this what]
    (handler-fn this what)))

(defn get-ipc [settings]
  (map->IPC (merge {:command-fn command*
                    :handler-fn handler*}
                   settings
                   {:values (atom {:volume/global-volume 30
                                   :volume/local-volume 20
                                   :volume/network-volume 20
                                   :jam/playout-delay 10
                                   :network/local-ip "192.168.1.100"
                                   :network/gateway-ip "192.168.1.1"
                                   :network/netmask-ip "255.255.255.0"})})))
