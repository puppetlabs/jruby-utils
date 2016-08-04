(def ks-version "1.3.0")
(def tk-version "1.3.1")

(defproject puppetlabs/jruby-utils "0.2.1-SNAPSHOT"
  :description "A library for working with JRuby"
  :url "https://github.com/puppetlabs/jruby-utils"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :pedantic? :abort

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :test-paths ["test/unit" "test/integration"]

  :dependencies [[org.clojure/clojure "1.7.0"]

                 ;; begin version conflict resolution dependencies
                 [cheshire "5.6.1"]
                 [clj-time "0.11.0"]
                 [org.slf4j/slf4j-api "1.7.13"]
                 ;; end version conflict resolution dependencies

                 [org.jruby/jruby-core "1.7.20.1"
                  :exclusions [com.github.jnr/jffi com.github.jnr/jnr-x86asm]]
                 ;; jffi and jnr-x86asm are explicit dependencies because,
                 ;; in JRuby's poms, they are defined using version ranges,
                 ;; and :pedantic? :abort won't tolerate this.
                 [com.github.jnr/jffi "1.2.9"]
                 [com.github.jnr/jffi "1.2.9" :classifier "native"]
                 [com.github.jnr/jnr-x86asm "1.0.2"]
                 ;; NOTE: jruby-stdlib packages some unexpected things inside
                 ;; of its jar; please read the detailed notes above the
                 ;; 'uberjar-exclusions' example toward the end of this file.
                 [org.jruby/jruby-stdlib "1.7.20.1"]


                 [org.clojure/tools.logging "0.3.1"]
                 [me.raynes/fs "1.4.6"]
                 [prismatic/schema "1.1.0"]
                 [slingshot "0.12.2"]


                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/ring-middleware "1.0.0"]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  ;; By declaring a classifier here and a corresponding profile below we'll get an additional jar
  ;; during `lein jar` that has all the code in the test/ directory. Downstream projects can then
  ;; depend on this test jar using a :classifier in their :dependencies to reuse the test utility
  ;; code that we have.
  :classifiers [["test" :testutils]]

  :profiles {:dev {:dependencies  [[puppetlabs/kitchensink ~ks-version :classifier "test" :scope "test"]
                                   [puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]]}
             :testutils {:source-paths ^:replace ["test/unit" "test/integration"]}}
  )
