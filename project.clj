(defproject songpark-jam "0.1.0-SNAPSHOT"

  :dependencies [[org.clojure/clojure "1.10.3"]
                 ;; structure
                 [com.stuartsierra/component "1.0.0" :scope "provided"]
                 ;; logging
                 [com.taoensso/timbre "5.1.2" :scope "provided"]]

  :repl-options {:init-ns songpark.jam.tpx}

  :plugins [[lein-auto "0.1.3"]]

  :profiles {:dev {:dependencies [[clj-commons/spyscope "0.1.48"]]
                   :injections [(require 'spyscope.core)]}})
