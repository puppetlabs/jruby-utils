(ns puppetlabs.services.jruby-pool-manager.jruby-internal-test
  (:require [clojure.test :refer :all]
            [clojure.java.jmx :as jmx]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.kitchensink.core :as ks]
            [me.raynes.fs :as fs])
  (:import (java.io StringReader)
           (com.puppetlabs.jruby_utils.pool JRubyPool)
           (org.jruby RubyInstanceConfig$CompileMode CompatVersion RubyInstanceConfig$ProfilingMode)
           (org.jruby.util.cli Options)
           (clojure.lang ExceptionInfo)))

;; Clear changes to JRuby management settings after each test.
(use-fixtures :each (fn [f] (f) (.unforce Options/MANAGEMENT_ENABLED)))

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
    (let [pool (JRubyPool. 2)
          profiler-file (str (ks/temp-file-name "foo"))
          config (logutils/with-test-logging
                  (jruby-testutils/jruby-config
                   {:compile-mode :jit
                    :profiler-output-file profiler-file
                    :profiling-mode :flat}))
          instance (jruby-internal/create-pool-instance! pool 0 config)
          instance-two (jruby-internal/create-pool-instance! pool 1 config)
          container (:scripting-container instance)
          container-two (:scripting-container instance-two)]
      (try
        (is (= RubyInstanceConfig$CompileMode/JIT
              (.getCompileMode container)))
        (is (= RubyInstanceConfig$ProfilingMode/FLAT
               (.getProfilingMode container)))
        (finally
          (.terminate container)
          (.terminate container-two)))
      ;; Because we add the current datetime and scripting container
      ;; hashcode to the filename we need to glob for it here.
      (let [profiler-files (fs/glob (fs/parent profiler-file)
                                    (str (fs/base-name profiler-file) "*"))
            real-profiler-file (first
                                profiler-files)]
        (is (= 2 (count profiler-files)))
        (is (not-empty (slurp real-profiler-file)))))))

(deftest default-compile-mode
  (testing "default compile-mode changes based on jruby version"
    (let [pool (JRubyPool. 1)
          config (logutils/with-test-logging
                  (jruby-testutils/jruby-config {}))
          instance (jruby-internal/create-pool-instance! pool 0 config)
          container (:scripting-container instance)]
      (try
        (is (= RubyInstanceConfig$CompileMode/JIT
               (.getCompileMode container)))
        (finally
          (.terminate container))))))

(deftest jruby-thread-dump
  (testing "returns an error when jruby.management.enabled is set to false"
    (.force Options/MANAGEMENT_ENABLED "false")
    (let [pool (JRubyPool. 1)
          config (logutils/with-test-logging
                  (jruby-testutils/jruby-config {}))
          instance (jruby-internal/create-pool-instance! pool 0 config)
          result (jruby-internal/get-instance-thread-dump instance)]
      (is (some? (:error result)))
      (is (re-find #"JRuby management interface not enabled" (:error result)))))
  (testing "returns a thread dump when jruby.management.enabled is set to true"
    (.force Options/MANAGEMENT_ENABLED "true")
    (let [pool (JRubyPool. 1)
          config (logutils/with-test-logging
                  (jruby-testutils/jruby-config {}))
          instance (jruby-internal/create-pool-instance! pool 0 config)
          _ (-> (:scripting-container instance)
                (.runScriptlet (StringReader.
                                "def naptime
                                   Kernel.sleep(1)
                                 end

                                Thread.new {naptime}")
                               "jruby-thread-dump.rb"))
          result (jruby-internal/get-instance-thread-dump instance)]
      (is (some? (:thread-dump result)))
      (is (re-find #"jruby-thread-dump\.rb" (:thread-dump result)))))
  (testing "returns an error if an exception is raised"
    (.force Options/MANAGEMENT_ENABLED "true")
    (let [pool (JRubyPool. 1)
          config (logutils/with-test-logging
                  (jruby-testutils/jruby-config {}))
          instance (jruby-internal/create-pool-instance! pool 0 config)
          mbean-name (jruby-internal/jmx-bean-name instance "Runtime")
          _ (jmx/unregister-mbean mbean-name)
          failing-mbean (proxy [org.jruby.management.Runtime]
                          [(jruby-internal/get-jruby-runtime instance)]
                          (threadDump []
                            (throw (Exception. "thread dump exception"))))
          _ (jmx/register-mbean failing-mbean mbean-name)
          result (logutils/with-test-logging
                  (jruby-internal/get-instance-thread-dump instance))]
      (is (some? (:error result)))
      (is (re-find #"Exception raised while generating thread dump" (:error result))))))
