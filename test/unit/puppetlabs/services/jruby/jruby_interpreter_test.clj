(ns puppetlabs.services.jruby.jruby-interpreter-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.impl.jruby-internal :as jruby-internal]))

(deftest jruby-env-vars
  (testing "the environment used by the JRuby interpreters"
    (let [jruby-interpreter (jruby-internal/create-scripting-container
                              (jruby-testutils/jruby-config))
          jruby-env (.runScriptlet jruby-interpreter "ENV")]

      ; $HOME and $PATH are left in by `jruby-env`
      (is (= #{"HOME" "PATH" "GEM_HOME" "JARS_NO_REQUIRE" "JARS_REQUIRE"}
            (set (keys jruby-env)))))))
