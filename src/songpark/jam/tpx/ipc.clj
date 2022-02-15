(ns songpark.jam.tpx.ipc
  (:require [taoensso.timbre :as log]))

(defprotocol IIPC
  (command [this what data])
  (handler [this what])
  (add-handler-callback [this what fn])
  (remove-handler-callback [this what]))

(defn command* [{:keys [values callbacks] :as _ipc} what data]
  (assert (contains? @callbacks what) (str what " is not a supported call to IPC"))
  (swap! values what data))

(defn handler* [{:keys [values callbacks] :as _ipc} what]
  (assert (contains? @callbacks what) (str what " is not a supported call to IPC"))
  ;; for testing purposes
  (Thread/sleep 100)
  (let [callback-fn (get @callbacks what)]
    (callback-fn (get @values what) {:context-map? true
                                     :testing? true
                                     :what what})))

(defn add-handler-callback* [this what fn]
  (swap! (:callbacks) assoc what fn))

(defn remove-handler-callback* [this what]
  (swap! (:callbacks) dissoc what))


(defrecord IPC [callbacks values command-fn handler-fn]
  IIPC
  (command [this what value]
    (command-fn this what value))
  (handler [this what]
    (handler-fn this what))
  (add-handler-callback [this what fn]
    (add-handler-callback* this what fn))
  (remove-handler-callback [this what]
    (remove-handler-callback* this what)))

(defn callback-test-fn [data context]
  (log/debug data context))

(defn get-ipc [settings]
  (map->IPC (merge {:callbacks (atom {:volume/global-volume callback-test-fn
                                      :volume/local-volume callback-test-fn
                                      :volume/network-volume callback-test-fn
                                      :jam/playout-delay callback-test-fn
                                      :network/local-ip callback-test-fn
                                      :network/gateway-ip callback-test-fn
                                      :network/netmask-ip callback-test-fn
                                      :sip/registered callback-test-fn
                                      :sip/call callback-test-fn
                                      :sip/connect callback-test-fn
                                      :sip/hangup callback-test-fn
                                      :stream/starting callback-test-fn
                                      :stream/syncing callback-test-fn
                                      :stream/failed callback-test-fn
                                      :stream/streaming callback-test-fn})
                    :command-fn command*
                    :handler-fn handler*}
                   settings
                   {:values (atom {:volume/global-volume 30
                                   :volume/local-volume 20
                                   :volume/network-volume 20
                                   :jam/playout-delay 10
                                   :network/local-ip "192.168.1.100"
                                   :network/gateway-ip "192.168.1.1"
                                   :network/netmask-ip "255.255.255.0"})})))
