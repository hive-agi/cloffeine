(defproject com.appsflyer/cloffeine "1.1.0"
  :description "A wrapper over https://github.com/ben-manes/caffeine"
  :url "https://github.com/AppsFlyer/cloffeine"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[com.github.ben-manes.caffeine/caffeine "3.2.4"]]
  :plugins [[lein-codox "0.10.8"]]
  :codox {:output-path "codox"
          :source-uri  "http://github.com/AppsFlyer/cloffeine/blob/{version}/{filepath}#L{line}"
          :metadata    {:doc/format :markdown}}
  :profiles {:uberjar {:aot :all}
             :dev     {:plugins      [[jonase/eastwood "1.4.3"]
                                      [lein-eftest "0.6.0"]
                                      [lein-kibit "0.1.11"]
                                      [com.jakemccrary/lein-test-refresh "0.26.0"]
                                      [lein-cloverage "1.2.4"]
                                      [lein-ancient "1.0.0"]]
                       :eftest       {:multithread?   false
                                      :report         eftest.report.junit/report
                                      :report-to-file "target/junit.xml"}
                       :dependencies [[org.clojure/clojure "1.12.5"]
                                      [org.clojure/test.check "1.1.3"]
                                      [criterium "0.4.6"]
                                      [cheshire "6.2.0"]
                                      [com.taoensso/timbre "6.8.0"]
                                      [clj-kondo "RELEASE"]
                                      [funcool/promesa "12.0.1"]
                                      [com.google.guava/guava-testlib "33.6.0-jre"]]
                       :aliases      {"clj-kondo" ["run" "-m" "clj-kondo.main"]
                                      "lint"      ["run" "-m" "clj-kondo.main" "--lint" "src" "test"]}
                       :global-vars  {*warn-on-reflection* true}}})
