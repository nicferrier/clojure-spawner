(defproject spawner "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [me.raynes/conch "0.8.0"]
                 [org.clojure/core.async "0.1.256.0-1bf8cf-alpha"]
                 [http-kit "2.1.6"]]
  :main ^:skip-aot spawner.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
