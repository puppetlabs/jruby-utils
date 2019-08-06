(ns puppetlabs.jruby-utils.slj4j-logger-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils])
  (:import (org.jruby.util.log LoggerFactory)))

(deftest slf4j-logger-test
  (let [actual-logger-name "my-test-logger"
        exception-message "exceptionally bad news"
        expected-logger-name (str "jruby." actual-logger-name)
        logger (LoggerFactory/getLogger actual-logger-name)
        actual-log-event (fn [event]
                           (assoc event :exception
                                        (when-let [exception (:exception event)]
                                          (.getMessage exception))))
        expected-log-event (fn [message level exception]
                             {:message message
                              :level level
                              :exception exception
                              :logger expected-logger-name})]
    (testing "name stored in logger"
      (is (= expected-logger-name (.getName logger))))

    (testing "warn with a string and objects"
      (logutils/with-test-logging
       (.warn logger "a {} {} warning" (into-array Object ["strongly" "worded"]))
       (is (logged? "a strongly worded warning" :warn))))
    (testing "warn with an exception"
      (logutils/with-test-logging
       (.warn logger (Exception. exception-message))
       (is (logged?
            #(= (expected-log-event "" :warn exception-message)
                (actual-log-event %))))))
    (testing "warn with a string and an exception"
      (logutils/with-test-logging
       (.warn logger "a warning" (Exception. exception-message))
       (is (logged?
            #(= (expected-log-event "a warning" :warn exception-message)
                (actual-log-event %))))))

    (testing "error with a string and objects"
      (logutils/with-test-logging
       (.error logger "a {} {} error" (into-array Object ["strongly" "worded"]))
       (is (logged? "a strongly worded error" :error))))
    (testing "error with an exception"
      (logutils/with-test-logging
       (.error logger (Exception. exception-message))
       (is (logged?
            #(= (expected-log-event "" :error exception-message)
                (actual-log-event %))))))
    (testing "error with a string and an exception"
      (logutils/with-test-logging
       (.error logger "an error" (Exception. exception-message))
       (is (logged?
            #(= (expected-log-event "an error" :error exception-message)
                (actual-log-event %))))))

    (testing "info with a string and objects"
      (logutils/with-test-logging
       (.info logger
              "some {} {} info"
              (into-array Object ["strongly" "worded"]))
       (is (logged? "some strongly worded info" :info))))
    (testing "info with an exception"
      (logutils/with-test-logging
       (.info logger (Exception. exception-message))
       (is (logged?
            #(= (expected-log-event "" :info exception-message)
                (actual-log-event %))))))
    (testing "info with a string and an exception"
      (logutils/with-test-logging
       (.info logger "some info" (Exception. exception-message))
       (is (logged?
            #(= (expected-log-event "some info" :info exception-message)
                (actual-log-event %))))))

    (testing "debug with a string and objects"
      (logutils/with-test-logging
       (.debug logger
               "some {} {} debug"
               (into-array Object ["strongly" "worded"]))
       (is (logged? "some strongly worded debug" :debug))))
    (testing "info with an exception"
      (logutils/with-test-logging
       (.debug logger (Exception. exception-message))
       (is (logged?
            #(= (expected-log-event "" :debug exception-message)
                (actual-log-event %))))))
    (testing "debug with a string and an exception"
      (logutils/with-test-logging
       (.debug logger "some debug" (Exception. exception-message))
       (is (logged?
            #(= (expected-log-event "some debug" :debug exception-message)
                (actual-log-event %))))))))
