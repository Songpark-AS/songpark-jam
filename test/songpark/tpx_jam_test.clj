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

(defn sleep
  ([] (sleep 1000))
  ([ms] (Thread/sleep ms)))

(defn fake-command [ipc what data]
  (tpx.ipc/command ipc what data)
  (tpx.ipc/handler ipc what)
  (sleep 1000))



(deftest tpx-jam
  (let [client (atom nil)
        tp-id "tpx1"
        tps ["tpx1" "tpx2"]
        sips {"tpx1" "tpx1@voip1.inonit.no"
              "tpx2" "tpx2@void1.inonit.no"}
        _ (reset! client (init-client tp-id))
        ipc (component/start (tpx.ipc/get-ipc {}))
        jam (component/start (jam.tpx/get-jam {:tp-id tp-id
                                               :ipc ipc
                                               :mqtt-client @client
                                               :saved-values (atom {:volume/global-volume 20
                                                                    :volume/local-volume 15
                                                                    :volume/network-volume 10
                                                                    :jam/playout-delay 10})}))
        jam-data {:jam/sip sips
                  :jam/members tps
                  :jam/id "myjamid"}]

    (testing "Join jam"
      (do (tpx.ipc/reset-history! ipc)
          (jam.tpx/join jam jam-data)
          (is (= (tpx.ipc/get-history ipc) [[:sip/call "tpx2@void1.inonit.no"]]))))

    (testing "Join and leave jam"
      (do (tpx.ipc/reset-history! ipc)
          (jam.tpx/join jam jam-data)
          (jam.tpx/leave jam)
          (is (= (tpx.ipc/get-history ipc) [[:sip/call "tpx2@void1.inonit.no"]
                                            [:sip/hangup "tpx2@void1.inonit.no"]]))))

    (testing "Full call"
      (do (tpx.ipc/reset-history! ipc)
          (jam.tpx/join jam jam-data)
          (fake-command ipc :sip/making-call 0)
          (fake-command ipc :sip/calling 0)
          (fake-command ipc :sip/in-call 0)
          (fake-command ipc :stream/connecting 0)
          (fake-command ipc :stream/syncing 0)
          (fake-command ipc :stream/streaming 0)
          (jam.tpx/leave jam)
          (is (= (tpx.ipc/get-history ipc) [[:sip/call "tpx2@void1.inonit.no"]
                                            [:sip/making-call 0]
                                            [:sip/calling 0]
                                            [:sip/in-call 0]
                                            [:stream/connecting 0]
                                            [:stream/syncing 0]
                                            [:stream/streaming 0]
                                            [:sip/hangup "tpx2@void1.inonit.no"]]))))

    (testing "Incoming call"
      (do (tpx.ipc/reset-history! ipc)
          (jam.tpx/join jam jam-data)
          (fake-command ipc :sip/incoming-call 0)
          (fake-command ipc :sip/in-call 0)
          (fake-command ipc :stream/connecting 0)
          (fake-command ipc :stream/syncing 0)
          (fake-command ipc :stream/streaming 0)
          (jam.tpx/leave jam)
          (is (= (tpx.ipc/get-history ipc) [[:sip/call "tpx2@void1.inonit.no"]
                                            [:sip/incoming-call 0]
                                            [:sip/in-call 0]
                                            [:stream/connecting 0]
                                            [:stream/syncing 0]
                                            [:stream/streaming 0]
                                            [:sip/hangup "tpx2@void1.inonit.no"]]))))


    
    (component/stop jam)
    (component/stop ipc)
    (stop @client)))

