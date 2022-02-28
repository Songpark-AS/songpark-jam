(ns songpark.util-test
  (:require [com.stuartsierra.component :as component]
            [songpark.mqtt :as mqtt]))


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

(defn sleep
  ([] (sleep 1000))
  ([ms] (Thread/sleep ms)))

(defn tick? [t]
  (instance? java.time.Instant t))
