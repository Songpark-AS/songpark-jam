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
                             :teleporter/ip "10.100.200.104"}
                     tp-id2 {:teleporter/id tp-id2
                             :teleporter/ip "10.100.200.106"}
                     tp-id3 {:teleporter/id tp-id3
                             :teleporter/ip "10.100.200.108"}
                     tp-id4 {:teleporter/id tp-id4
                             :teleporter/ip "10.100.200.110"}}]
    (proto/write-db db [:teleporter] teleporters)
    (proto/write-db db [:jam] {})

    (testing "jam start"
      (jam.platform/start jam-manager [tp-id1 tp-id2])
      (let [jam (proto/read-db db [:jam])]
        (is (not (empty? jam)))))
    (testing "jam stop"
      (let [jam-id (-> (proto/read-db db [:jam])
                       ffirst)]
        (jam.platform/stop jam-manager jam-id)
        (let [jam (proto/read-db db [:jam jam-id])]
          (is (and (tick? (:jam/timeout jam))
                   (= :stopping (:jam/status jam)))))))
    (component/stop jam-manager)
    (stop @client)))
