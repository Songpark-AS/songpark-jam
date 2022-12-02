(ns songpark.platform-jam-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [songpark.jam.platform :as jam.platform]
            [songpark.jam.platform.protocol :as proto]
            [songpark.util-test :refer [get-config
                                        init-client
                                        start
                                        stop
                                        sleep
                                        tick?
                                        tp-id1 tp-id2 tp-id3 tp-id4]]))



(deftest platform-jam
  (let [client (atom nil)
        platform-id "platform"
        db (jam.platform/mem-db)
        _ (reset! client (init-client platform-id))
        jam-manager (component/start (jam.platform/jam-manager {:db db
                                                                :timeout-ms-waiting (* 5 1000)
                                                                :timeout-ms-jam-eol (* 5 1000)
                                                                :mqtt-client @client}))
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
                             :teleporter/public-ip "10.100.200.40"}}]
    (proto/write-db db [:teleporter] teleporters)
    (proto/write-db db [:jams] {})

    (testing "jam start"
      (jam.platform/start jam-manager [tp-id1 tp-id2])
      (let [jam (proto/read-db db [:jams])]
        (is (not (empty? jam)))))
    (testing "jam stop"
      (let [jam-id (-> (proto/read-db db [:jams])
                       ffirst)]
        (jam.platform/stop jam-manager jam-id)
        (let [jam (proto/read-db db [:jams jam-id])]
          (is (and (tick? (:jam/timeout jam))
                   (= :stopping (:jam/status jam)))))))
    (component/stop jam-manager)
    (stop @client)))
