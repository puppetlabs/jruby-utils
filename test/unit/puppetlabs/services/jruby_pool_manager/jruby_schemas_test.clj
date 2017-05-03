(ns puppetlabs.services.jruby-pool-manager.jruby-schemas-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [dynapath.util :as dynapath]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests

(deftest using-jruby-9k?-test
  (testing "using-jruby-9k? returns proper value for loaded jruby"
    (let [jruby-9k-jar-in-classpath? (or (some #(str/includes? (.getPath %)
                                                               "jruby-core-9.")
                                               (dynapath/all-classpath-urls))
                                         false)]
      (is (= jruby-9k-jar-in-classpath? jruby-schemas/using-jruby-9k?)))))
