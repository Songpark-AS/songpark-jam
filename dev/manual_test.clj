(ns manual-test
  (:require [com.stuartsierra.component :as component]
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


(comment


  (do ;; start

    ;; platform
    (do (def client-platform (atom nil))
        (def platform-id "platform")
        (def db (jam.platform/mem-db))
        (reset! client-platform (init-client platform-id))
        (def jam-manager (->> {:db db
                               :timeout-ms-waiting (* 5 1000)
                               :timeout-ms-jam-eol (* 5 1000)
                               :mqtt-client @client-platform}
                              (jam.platform/jam-manager)
                              (component/start)))

        (def teleporters {tp-id1 {:teleporter/id tp-id1
                                  :teleporter/ip "10.100.200.104"}
                          tp-id2 {:teleporter/id tp-id2
                                  :teleporter/ip "10.100.200.106"}
                          tp-id3 {:teleporter/id tp-id3
                                  :teleporter/ip "10.100.200.108"}
                          tp-id4 {:teleporter/id tp-id4
                                  :teleporter/ip "10.100.200.110"}})

        (proto/write-db db [:teleporter] teleporters)
        (proto/write-db db [:jam] {})

        (mqtt/add-injection @client-platform :jam-manager jam-manager))

    ;; teleporters
    (do (def client-tpx1 (atom nil))
        (def client-tpx2 (atom nil))
        (def tp1-data {:teleporter/id tp-id1
                       :teleporter/ip "10.100.200.30"})
        (def tp2-data {:teleporter/id tp-id2
                       :teleporter/ip "10.100.200.40"})
        (def members [tp1-data
                      tp2-data])
        (reset! client-tpx1 (init-client tp-id1))
        (reset! client-tpx2 (init-client tp-id2))

        (def ipc1 (component/start (tpx.ipc/get-ipc {})))
        (def ipc2 (component/start (tpx.ipc/get-ipc {})))
        (def tpx1 (component/start (jam.tpx/get-jam {:tp-id tp-id1
                                                     :ipc ipc1
                                                     :mqtt-client @client-tpx1})))
        (def tpx2 (component/start (jam.tpx/get-jam {:tp-id tp-id2
                                                     :ipc ipc2
                                                     :mqtt-client @client-tpx2})))
        (mqtt/add-injection @client-tpx1 :tpx tpx1)
        (mqtt/add-injection @client-tpx2 :tpx tpx2)

        (log/debug :tpx1/state (jam.tpx/get-state tpx1))
        (log/debug :tpx2/state (jam.tpx/get-state tpx2))))


  ;; start jamx
  (jam.platform/start jam-manager [tp-id1 tp-id2])
  (let [jam (proto/read-db db [:jams])]
    (not (empty? jam)))

  ;; clear history of fake ipc
  (do (tpx.ipc/reset-history! ipc1)
      (tpx.ipc/reset-history! ipc2))

  [(jam.tpx/get-state tpx1)
   (jam.tpx/get-state tpx2)]

  {:tpx1/data @(:data tpx1)
   :tpx2/data @(:data tpx2)}

  ;; ipc1 history
  (tpx.ipc/get-history ipc1)
  ;; ipc2 history
  (tpx.ipc/get-history ipc2)

  ;; stop the jam
  (let [jam-id (->> (proto/read-db db [:jams])
                    ffirst)]

    (jam.platform/stop jam-manager jam-id))

  (jam.platform/check-for-timeouts jam-manager)


  (do
    ;; stop
   ;; stop teleporters
   (do
     (component/stop tpx1)
     (component/stop tpx2)
     (component/stop ipc1)
     (component/stop ipc2)
     (stop @client-tpx1)
     (stop @client-tpx2))

   ;; stop platform
   (do
     (component/stop jam-manager)
     (stop @client-platform)))
  )
