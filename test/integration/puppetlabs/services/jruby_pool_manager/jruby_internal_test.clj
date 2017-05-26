(ns puppetlabs.services.jruby-pool-manager.jruby-internal-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils])
  (:import (com.puppetlabs.jruby_utils.pool JRubyPool)
           (org.jruby RubyInstanceConfig$CompileMode CompatVersion)
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

(deftest settings-plumbed-into-jruby-container
  (testing "settings plumbed into jruby container"
    (let [pool (JRubyPool. 1)
          config (logutils/with-test-logging
                  (jruby-testutils/jruby-config
                   {:compile-mode :jit}))
          instance (jruby-internal/create-pool-instance! pool 0 config #())
          container (:scripting-container instance)]
      (try
        (is (= RubyInstanceConfig$CompileMode/JIT
            (.getCompileMode container)))
        (when-not jruby-schemas/using-jruby-9k?
          (is (= CompatVersion/RUBY1_9 (.getCompatVersion container))
              "Unexpected default compat version configured for JRuby 1.7 container"))
        (finally
          (.terminate container))))))
