(ns songpark.platform-jam-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [songpark.jam.platform :as jam.platform]
            [songpark.jam.platform.protocol :as proto]
            [songpark.util-test :refer [get-config
                                        start
                                        stop
                                        init-client
                                        sleep
                                        tick?]]))



(deftest platform-jam
  (let [client (atom nil)
        platform-id "platform"
        db (jam.platform/mem-db)
        _ (reset! client (init-client platform-id))
        jam-manager (component/start (jam.platform/jam-manager {:db db
                                                                :timeout-ms (* 5 1000)
                                                                :mqtt-client @client}))
        teleporters {"tp1" {:teleporter/id "tp1"
                            :teleporter/sip "tp1@voip1.inonit.no"}
                     "tp2" {:teleporter/id "tp2"
                            :teleporter/sip "tp2@voip1.inonit.no"}
                     "tp3" {:teleporter/id "tp3"
                            :teleporter/sip "tp3@voip1.inonit.no"}
                     "tp4" {:teleporter/id "tp4"
                            :teleporter/sip "tp4@voip1.inonit.no"}}]
    (proto/write-db db [:teleporter] teleporters)
    (proto/write-db db [:jam] {})

    (testing "ask"
      (jam.platform/ask jam-manager "tp1")
      (is (tick? (proto/read-db db [:waiting "tp1"]))))
    (testing "jam starting"
      (jam.platform/ask jam-manager "tp2")
      (let [jam (proto/read-db db [:jam])]
        (is (not (empty? jam)))))
    (testing "jam stop"
      (let [jam-id (-> (proto/read-db db [:jam])
                       ffirst)]
        (jam.platform/stop jam-manager jam-id))
      (let [jam (proto/read-db db [:jam])]
        (is (empty? jam))))
    (testing "both jam and waiting are empty"
      (let [jam (proto/read-db db [:jam])
            waiting (proto/read-db db [:waiting])]
        (is (and (empty? jam)
                 (empty? waiting)))))
    (testing "ask timed out"
      (jam.platform/ask jam-manager "tp3")
      (sleep 6000)
      (jam.platform/check-for-timeouts jam-manager)
      (is (nil? (proto/read-db db [:waiting "tp3"]))))
    
    (component/stop jam-manager)
    (stop @client)))
