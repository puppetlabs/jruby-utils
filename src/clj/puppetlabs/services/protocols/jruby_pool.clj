(ns puppetlabs.services.protocols.jruby-pool)

(defprotocol JRubyPool
  (clear-pool! [this refill? on-complete] "")
  (clear-instance! [this instance cleanup-fn] ""))