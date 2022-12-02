(ns songpark.tpx-jam-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [songpark.jam.tpx :as jam.tpx]
            [songpark.jam.tpx.handler]
            [songpark.jam.tpx.ipc :as tpx.ipc]
            [songpark.util-test :refer [fake-command
                                        init-client
                                        sleep
                                        start
                                        stop
                                        tp-id1 tp-id2]]
            [songpark.mqtt :as mqtt]))

(defn- reset-to-idle! [tpx]
  (reset! (:data tpx) {:state :idle}))

(deftest tpx-jam
  (let [client1 (atom nil)
        client2 (atom nil)

        tp1-data {:teleporter/id tp-id1
                  :teleporter/local-ip "10.100.200.10"
                  :teleporter/public-ip "10.100.200.10"}
        tp2-data {:teleporter/id tp-id2
                  :teleporter/local-ip "20.100.200.10"
                  :teleporter/public-ip "10.100.200.20"}
        members [tp1-data
                 tp2-data]
        _ (reset! client1 (init-client tp-id1))
        _ (reset! client2 (init-client tp-id2))
        ipc1 (component/start (tpx.ipc/get-ipc {}))
        ipc2 (component/start (tpx.ipc/get-ipc {}))
        tpx1 (component/start (jam.tpx/get-jam {:tp-id tp-id1
                                                :ipc ipc1
                                                :mqtt-client @client1}))
        tpx2 (component/start (jam.tpx/get-jam {:tp-id tp-id2
                                                :ipc ipc2
                                                :mqtt-client @client2}))
        jam-data {:jam/members members
                  :jam/id "myjamid"}]

    (mqtt/add-injection @client1 :tpx tpx1)
    (mqtt/add-injection @client2 :tpx tpx2)

    (testing "Join jam"
      (do (tpx.ipc/reset-history! ipc1)
          (reset-to-idle! tpx1)
          (jam.tpx/join tpx1 jam-data)
          (is (= @(:data tpx1) (assoc jam-data :state :jam/joined)))))

    (testing "Receive call"
      (do (tpx.ipc/reset-history! ipc1)
          (reset-to-idle! tpx1)
          (jam.tpx/join tpx1 jam-data)
          (jam.tpx/receive-call tpx1)
          (is (= (tpx.ipc/get-history ipc1) [[:call/receive (assoc tp2-data :teleporter/port jam.tpx/port)]]))))

    (testing "Initiate call"
      (do (reset-to-idle! tpx1)
          (reset-to-idle! tpx2)
          (tpx.ipc/reset-history! ipc1)
          (tpx.ipc/reset-history! ipc2)
          (jam.tpx/join tpx1 jam-data)
          (Thread/sleep 2000) ;; let the mqtt message from before arrive
          (jam.tpx/join tpx2 jam-data)
          (jam.tpx/initiate-call tpx2)
          (is (= (tpx.ipc/get-history ipc2) [[:call/initiate (assoc tp1-data :teleporter/port jam.tpx/port)]]))))

    (testing "Full call, happy path"
      (do (reset-to-idle! tpx1)
          (reset-to-idle! tpx2)
          (tpx.ipc/reset-history! ipc1)
          (tpx.ipc/reset-history! ipc2)
          (jam.tpx/join tpx1 jam-data)
          (jam.tpx/join tpx2 jam-data)
          (jam.tpx/start-call tpx1)
          (Thread/sleep 1000)
          (jam.tpx/start-call tpx2)
          (jam.tpx/stop-call tpx1)
          (jam.tpx/stop-call tpx2)

          (do (is (= (tpx.ipc/get-history ipc1) [[:call/receive (assoc tp2-data :teleporter/port jam.tpx/port)]
                                              [:jam/stop-coredump true]
                                                 [:call/stop true]]))
              (is (= (tpx.ipc/get-history ipc2) [[:call/initiate (assoc tp1-data :teleporter/port jam.tpx/port)]
                                                 [:jam/stop-coredump true]
                                                 [:call/stop true]])))))

    (component/stop tpx1)
    (component/stop tpx2)
    (component/stop ipc1)
    (component/stop ipc2)
    (stop @client1)
    (stop @client2)))
