(ns songpark.jam.tpx.handler
  (:require [songpark.jam.tpx :as jam.tpx]
            [songpark.mqtt :as mqtt :refer [handle-message]]
            [taoensso.timbre :as log]))

(defmethod handle-message :jam.call/receive [{:keys [tpx mqtt-client] :as msg}]
  (let [data (:data tpx)
        {jam-id-this :jam/id} @data
        {jam-id-that :jam/id} msg]
    (log/debug :jam.call/receive {:jam-id-this jam-id-this
                                  :jam-id-that jam-id-that})
    (when (and (some? jam-id-this)
             (= jam-id-this jam-id-that))
          ;; the other teleporter is receiving, and we're in the same
          ;; jam. let's ininiate our call to the receiver
          (jam.tpx/initiate-call tpx))))
