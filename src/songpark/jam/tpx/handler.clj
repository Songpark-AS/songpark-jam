(ns songpark.jam.tpx.handler
  (:require [songpark.jam.tpx :as jam.tpx]
            [songpark.mqtt :as mqtt]))

(defmethod mqtt/handle-message :jam/invite [{:keys [jam mqtt-client] :as msg}]
  (jam.tpx/join jam (mqtt/clean-message mqtt-client msg)))

(defmethod mqtt/handle-message :jam/leave [{:keys [jam]}]
  (jam.tpx/leave jam))
