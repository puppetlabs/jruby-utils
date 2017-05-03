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

(deftest ^:integration settings-plumbed-into-jruby-container
  (testing "settings plumbed into jruby container"
    (let [pool (JRubyPool. 1)
          config (logutils/with-test-logging
                  (jruby-testutils/jruby-config
                   {:compile-mode :jit
                    :compat-version 2.0}))
          instance (jruby-internal/create-pool-instance! pool 0 config #())
          container (:scripting-container instance)]
      (try
        (is (= RubyInstanceConfig$CompileMode/JIT
            (.getCompileMode container)))
        (is (.is2_0 (.getCompatVersion container)))
        (finally
          (.terminate container))))))

(deftest get-compat-version-test
  (testing "returns correct compat version for SupportedJrubyCompatVersions enum"
    (when (contains? jruby-schemas/supported-jruby-compat-versions "1.9")
      (is (= CompatVersion/RUBY1_9 (jruby-internal/get-compat-version "1.9"))))
    (when (contains? jruby-schemas/supported-jruby-compat-versions "2.0")
      (is (= CompatVersion/RUBY2_0 (jruby-internal/get-compat-version "2.0"))))
    (when-not (contains? jruby-schemas/supported-jruby-compat-versions
                         jruby-core/default-jruby-compat-version)
      (is (nil? (jruby-internal/get-compat-version
                 jruby-core/default-jruby-compat-version)))))
  (testing "throws an exception if mode is nil"
    (is (thrown? ExceptionInfo
                 (jruby-internal/get-compat-version nil))))
  (testing "throws an exception for values not in enum"
    (is (thrown? ExceptionInfo
                 (jruby-internal/get-compat-version "foo")))))
