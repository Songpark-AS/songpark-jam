(ns songpark.handlers-test
  (:require [songpark.jam.platform :as jam.platform]
            [songpark.jam.tpx :as jam.tpx]
            [songpark.mqtt :refer [handle-message]]
            [taoensso.timbre :as log]))


;; teleporter handlers
(defmethod handle-message :jam.cmd/join [{:keys [tpx] :as msg}]
  (log/debug :jam.cmd/join {:mqtt-client/id (get-in msg [:mqtt-client :id])})
  (jam.tpx/join tpx msg))

(defmethod handle-message :jam.cmd/start [{:keys [tpx] :as msg}]
  (log/debug :jam.cmd/start {:mqtt-client/id (get-in msg [:mqtt-client :id])})
  (jam.tpx/start-call tpx))

(defmethod handle-message :jam.cmd/stop [{:keys [tpx] :as msg}]
  (log/debug :jam.cmd/stop {:mqtt-client/id (get-in msg [:mqtt-client :id])})
  (jam.tpx/stop-call tpx))

(defmethod handle-message :jam.cmd/reset [{:keys [tpx] :as msg}]
  (log/debug :jam.cmd/reset :mqtt-client/id (get-in msg [:mqtt-client :id]))
  (jam.tpx/reset tpx))


;; platform handlers
(defmethod handle-message :jam/joined [{:keys [jam-manager]
                                        teleporter-id :teleporter/id
                                        jam-id :jam/id
                                        :as _msg}]
  (log/debug :jam/joined (select-keys _msg [:message/type
                                            :jam/id
                                            :teleporter/id]))
  (jam.platform/joined jam-manager jam-id teleporter-id))

(defmethod handle-message :jam/left [{:keys [jam-manager]
                                        teleporter-id :teleporter/id
                                        jam-id :jam/id
                                        :as _msg}]
  (log/debug :jam/left (select-keys _msg [:message/type
                                            :jam/id
                                            :teleporter/id]))
  (jam.platform/left jam-manager jam-id teleporter-id))
