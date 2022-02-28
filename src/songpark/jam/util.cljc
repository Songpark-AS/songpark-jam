(ns songpark.jam.util)

(defn get-id []
  #?(:clj  (java.util.UUID/randomUUID)
     :cljs (random-uuid)))

(defn get-jam-topic-subscriptions
  "Get the subscriptions for a jam"
  [environment {:keys [jam/id] :as _jam-data}]
  (case environment
    :teleporter
    {(str id "/jam") 0}
    
    :platform
    {(str id "/jam") 0
     (str id "/teleporters/platform") 0}
    
    :app
    {(str id "/jam") 0
     (str id "/teleporters/app") 0}
    
    (throw (ex-info "No supported environment was found. Supported environments are :teleporter, :platform and :app" {:environment environment}))))

(defn get-jam-topic
  "Used togeter with what and the jam-data to get the topic to publish to"
  [what {:keys [jam/id] :as _jam-data}]
  (case what
    :teleporters/app
    (str id "/teleporters/app")

    :jam
    (str id "/jam")

    :teleporters/platform
    (str id "/teleporters/platform")
    
    (throw (ex-info "No supported topic found" {:what what
                                                :jam/id id}))))
