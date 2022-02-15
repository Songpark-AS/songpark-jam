(ns songpark.tpx-jam-test
  (:require [clojure.test :refer :all]
            [songpark.jam.tpx :as jam.tpx]
            [songpark.jam.tpx.ipc :as tpx.ipc]
            [com.stuartsierra.component :as component]
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


(deftest tpx-jam
  (let [client (atom nil)
        tp-id "tpx1"
        _ (reset! client (init-client tp-id))
        ipc (component/start (tpx.ipc/get-ipc {}))
        jam (component/start (jam.tpx/get-jam {:tp-id tp-id
                                               :ipc ipc
                                               :mqtt-client @client}))]

    (tpx.ipc/command ipc :sip/call "tpx2")
    (tpx.ipc/handler ipc :sip/call)

    (Thread/sleep 100)
    
    (component/stop jam)
    (component/stop ipc)
    (stop @client)))

