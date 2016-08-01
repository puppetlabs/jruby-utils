(ns puppetlabs.services.jruby-pool-manager.jruby-interpreter-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]))

(deftest jruby-env-vars
  (testing "the environment used by the JRuby interpreters"
    (let [jruby-interpreter (jruby-internal/create-scripting-container
                              (jruby-testutils/jruby-config))
          jruby-env (.runScriptlet jruby-interpreter "ENV")]
      ; $HOME and $PATH are left in by `jruby-env`
      (is (= #{"HOME" "PATH" "GEM_HOME" "JARS_NO_REQUIRE" "JARS_REQUIRE"}
             (set (keys jruby-env)))))))

(deftest jruby-env-vars
  (testing "the environment used by the JRuby interpreters"
    (let [jruby-interpreter (jruby-internal/create-scripting-container
                             (jruby-testutils/jruby-config {:environment-vars {"FOO" "for_jruby"}}))
          jruby-env (.runScriptlet jruby-interpreter "ENV")]
                                        ; $HOME and $PATH are left in by `jruby-env`
      (is (= #{"HOME" "PATH" "GEM_HOME" "JARS_NO_REQUIRE" "JARS_REQUIRE" "FOO"}
             (set (keys jruby-env))))
      (is (= (.get jruby-env "FOO") "for_jruby")))))
