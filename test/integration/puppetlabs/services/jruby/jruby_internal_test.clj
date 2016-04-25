(ns puppetlabs.services.jruby.jruby-internal-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-schemas :as jruby-schemas])
  (:import (com.puppetlabs.jruby_utils.pool JRubyPool)
           (org.jruby RubyInstanceConfig$CompileMode)
           (clojure.lang ExceptionInfo)))

(deftest get-compile-mode-test
  (testing "returns correct compile modes for SupportedJRubyCompileModes enum"
    (is (= RubyInstanceConfig$CompileMode/JIT
           (jruby-internal/get-compile-mode :jit)))
    (is (= RubyInstanceConfig$CompileMode/FORCE
           (jruby-internal/get-compile-mode :force)))
    (is (= RubyInstanceConfig$CompileMode/OFF
           (jruby-internal/get-compile-mode :off))))
  (testing "returns a valid CompileMode for all values of enum"
    (doseq [mode jruby-schemas/supported-jruby-compile-modes]
      (is (instance? RubyInstanceConfig$CompileMode
                     (jruby-internal/get-compile-mode mode)))))
  (testing "throws an exception if mode is nil"
    (is (thrown? ExceptionInfo
                 (jruby-internal/get-compile-mode nil))))
  (testing "throws an exception for values not in enum"
    (is (thrown? ExceptionInfo
                 (jruby-internal/get-compile-mode :foo)))))

(deftest ^:integration settings-plumbed-into-jruby-container
  (testing "setting plumbed into jruby container for"
    (let [pool (JRubyPool. 1)
          config (jruby-testutils/jruby-config
                  {:compile-mode :jit})
          instance (jruby-internal/create-pool-instance! pool 0 config #())
          container (:scripting-container instance)]
      (try
        (= RubyInstanceConfig$CompileMode/JIT
           (.getCompileMode container))
        (finally
          (.terminate container))))))
