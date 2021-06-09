(defproject org.martinklepsch/clj-http-lite "0.4.3"
  :description "A Clojure HTTP library similar to clj-http, but more lightweight."
  :url "https://github.com/martinklepsch/clj-http-lite/"
  :license {:name "MIT"
            :url "http://www.opensource.org/licenses/mit-license.php"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [slingshot "0.12.2"]]
  :profiles {:test {:dependencies   [[ring/ring-jetty-adapter "1.3.2"]
                                     [ch.qos.logback/logback-classic "1.2.3"
                                      :exclusions [org.slf4j/slf4j-api]]
                                     [org.slf4j/jcl-over-slf4j "1.7.26"]
                                     [org.slf4j/jul-to-slf4j "1.7.26"]
                                     [org.slf4j/log4j-over-slf4j "1.7.26"]]
                    :resource-paths ["test-resources"]}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.3"]]}}
  :test-selectors {:default     (constantly true)
                   :all         (constantly true)
                   :unit        #(not (:integration %))
                   :integration :integration}
  :checksum-deps true)
