(ns re-db.reactive.macros
  (:require [re-db.reactive :as-alias reactive])
  #?(:cljs (:require-macros re-db.reactive.macros)))

(defmacro set-swap! [sym f & args]
  `(set! ~sym (~f ~sym ~@args)))

(defmacro with-owner [owner & body]
  `(binding [reactive/*owner* ~owner] ~@body))

(defmacro capture-derefs! [& body]
  `(binding [reactive/*captured-derefs* (volatile! reactive/empty-derefs)]
     (let [val# (do ~@body)
           new-derefs# @reactive/*captured-derefs*]
       (reactive/handle-new-derefs! reactive/*owner* new-derefs#)
       val#)))

(defmacro without-deref-capture [& body]
  `(binding [reactive/*captured-derefs* nil] ~@body))

(defmacro support-hooks! [& body]
  `(binding [reactive/*hook-i* (volatile! -1)]
     ~@body))

(defmacro reaction
  "Returns a derefable reactive source based on body. Re-evaluates body when any dependencies (captured derefs) change. Lazy."
  [& body]
  `(reactive/make-reaction (fn [] ~@body)))

(defmacro reaction!
  "Eager version of reaction"
  [& body]
  `(doto (reactive/make-reaction (fn [] ~@body)) reactive/invalidate!))

;; for dev - only adds derefs
(defmacro with-session
  "Evaluates body, accumulating dependencies in session (for later disposal)"
  [session & body]
  `(binding [reactive/*captured-derefs* (volatile! reactive/empty-derefs)]
     (let [session# ~session
           val# (do ~@body)
           new-derefs# @reactive/*captured-derefs*]
       (doseq [producer# new-derefs#] (add-watch producer# session# (fn [& args#])))
       (reactive/set-derefs! session# (into (reactive/get-derefs session#) new-derefs#))
       val#)))

(defmacro session
  "[] - returns a session.
   [& body] - Evaluates body in a reactive session which is immediately disposed"
  ([] `(~'reactive/make-session))
  ([& body]
   `(let [s# (reactive/make-session)
          v# (with-session s# ~@body)]
      (reactive/dispose! s#)
      v#)))
