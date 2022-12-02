(ns songpark.util-test
  (:require [com.stuartsierra.component :as component]
            [songpark.jam.tpx.ipc :as tpx.ipc]
            [songpark.mqtt :as mqtt]))


(def tp-id1 #uuid "00000000-0000-0000-0000-000000000001")
(def tp-id2 #uuid "00000000-0000-0000-0000-000000000002")
(def tp-id3 #uuid "00000000-0000-0000-0000-000000000003")
(def tp-id4 #uuid "00000000-0000-0000-0000-000000000004")

(defn sleep
  ([] (sleep 1000))
  ([ms] (Thread/sleep ms)))

(defn fake-command [ipc what data]
  (tpx.ipc/command ipc what data)
  (tpx.ipc/handler ipc what)
  (sleep 1000))

(defn get-config []
  {:config {:host "127.0.0.1"
            :scheme "tcp"
            :port 1883
            :connect-options {:auto-reconnect true}}})

(defn start [config]
  (component/start (mqtt/mqtt-client config)))

(defn stop [mqtt-client]
  (component/stop mqtt-client))

(defn init-client [client-id]
  (start (assoc-in (get-config)
                   [:config :id] client-id)))

(defn tick? [t]
  (instance? java.time.Instant t))
