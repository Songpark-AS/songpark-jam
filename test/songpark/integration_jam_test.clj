(ns songpark.integration-jam-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [songpark.handlers-test]
            [songpark.jam.platform :as jam.platform]
            [songpark.jam.platform.protocol :as proto]
            [songpark.jam.tpx :as jam.tpx]
            [songpark.jam.tpx.handler]
            [songpark.jam.tpx.ipc :as tpx.ipc]
            [songpark.util-test :refer [fake-command
                                        get-config
                                        init-client
                                        sleep
                                        start
                                        stop
                                        tick?
                                        tp-id1 tp-id2 tp-id3 tp-id4]]
            [songpark.mqtt :as mqtt]
            [taoensso.timbre :as log]))




(deftest full-jam
  (let [;; platform
        client-platform (atom nil)
        platform-id "platform"
        db (jam.platform/mem-db)
        _ (reset! client-platform (init-client platform-id))
        jam-manager (->> {:db db
                          :timeout-ms-waiting (* 5 1000)
                          :timeout-ms-jam-eol (* 5 1000)
                          :mqtt-client @client-platform}
                         (jam.platform/jam-manager)
                         (component/start))

        teleporters {tp-id1 {:teleporter/id tp-id1
                             :teleporter/local-ip "10.0.0.10"
                             :teleporter/public-ip "10.100.200.10"}
                     tp-id2 {:teleporter/id tp-id2
                             :teleporter/local-ip "20.0.0.10"
                             :teleporter/public-ip "10.100.200.20"}
                     tp-id3 {:teleporter/id tp-id3
                             :teleporter/local-ip "30.0.0.10"
                             :teleporter/public-ip "10.100.200.30"}
                     tp-id4 {:teleporter/id tp-id4
                             :teleporter/local-ip "40.0.0.10"
                             :teleporter/public-ip "10.100.200.40"}}

        _ (proto/write-db db [:teleporter] teleporters)
        _ (proto/write-db db [:jam] {})

        _ (mqtt/add-injection @client-platform :jam-manager jam-manager)

        ;; teleporters
        client-tpx1 (atom nil)
        client-tpx2 (atom nil)
        tp1-data {:teleporter/id tp-id1
                  :teleporter/local-ip "10.0.0.10"
                  :teleporter/public-ip "10.100.200.10"}
        tp2-data {:teleporter/id tp-id2
                  :teleporter/local-ip "20.0.0.10"
                  :teleporter/public-ip "10.100.200.20"}
        members [tp1-data
                 tp2-data]
        _ (reset! client-tpx1 (init-client tp-id1))
        _ (reset! client-tpx2 (init-client tp-id2))

        ipc1 (component/start (tpx.ipc/get-ipc {}))
        ipc2 (component/start (tpx.ipc/get-ipc {}))
        tpx1 (component/start (jam.tpx/get-jam {:tp-id tp-id1
                                                :ipc ipc1
                                                :mqtt-client @client-tpx1}))
        tpx2 (component/start (jam.tpx/get-jam {:tp-id tp-id2
                                                :ipc ipc2
                                                :mqtt-client @client-tpx2}))
        _ (mqtt/add-injection @client-tpx1 :tpx tpx1)
        _ (mqtt/add-injection @client-tpx2 :tpx tpx2)]

    (log/debug ::state (jam.tpx/get-state tpx1))

    ;; let everything settle
    (Thread/sleep 1000)

    (testing "Integration between platform and Teleporters"
      (testing "Jam has started"
        (jam.platform/start jam-manager [tp-id1 tp-id2])
        (let [jams (proto/read-db db [:jams])]
          (is (not (empty? jams)))))

      (testing "Teleporter 1 is receiving"
        (Thread/sleep 2000)
        (is (= (tpx.ipc/get-history ipc1)
               [[:call/receive (assoc tp2-data :teleporter/port jam.tpx/port)]])))
      (testing "Teleporter 2 is calling"
        (Thread/sleep 2000)
        (is (= (tpx.ipc/get-history ipc2)
               [[:call/initiate (assoc tp1-data :teleporter/port jam.tpx/port)]])))
      (let [jam-id (->> (proto/read-db db [:jams])
                        ffirst)]
        (testing "Stopping the jam"
          (jam.platform/stop jam-manager jam-id)
          (let [jam (proto/read-db db [:jams jam-id])]
            (is (and (tick? (:jam/timeout jam))
                     (= :stopping (:jam/status jam))))))
        (Thread/sleep 1000)
        (testing "Teleporter 1 has stopped the call"
          (is (= (tpx.ipc/get-history ipc1)
                 [[:call/receive (assoc tp2-data :teleporter/port jam.tpx/port)]
                  [:jam/stop-coredump true]
                  [:call/stop true]])))
        (testing "Telporter 2 has stopped the call"
          (is (= (tpx.ipc/get-history ipc2)
                 [[:call/initiate (assoc tp1-data :teleporter/port jam.tpx/port)]
                  [:jam/stop-coredump true]
                  [:call/stop true]])))))


    ;; let everything settle
    (Thread/sleep 1000)

    (component/stop tpx1)
    (component/stop tpx2)
    (component/stop ipc1)
    (component/stop ipc2)
    (stop @client-tpx1)
    (stop @client-tpx2)

    (component/stop jam-manager)
    (stop @client-platform)))
