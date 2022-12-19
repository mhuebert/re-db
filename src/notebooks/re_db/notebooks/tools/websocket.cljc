(ns re-db.notebooks.tools.websocket
  "Small clojure(script) server(client) websocket API"
  (:require #?(:clj [org.httpkit.server :as http])
            [applied-science.js-interop :as j]
            [re-db.transit :as t]
            [re-db.sync :as sync]
            [re-db.util :as u]
            [re-db.api :as d]))

;; A full-stack websocket API, which can
;; 1. run a server which accepts websocket connections,
;; 2. connect to this server from a browser.
;; 3. handle messages on both sides, using a handler registry

;; To "handle" message vectors, we'll associate identifiers (the first position of the mvec)
;; with handler functions in an atom that we can `register` to when adding support for
;; new message types.


(def builtin-handlers
  {:sync/watched-result
   (fn [{:keys [result-handlers]} id target]
     (sync/handle-message result-handlers id target))
   :sync/watch
   (fn [{:as context :keys [resolve-ref channel]} ref-descriptor]
     (if-let [!ref (resolve-ref context ref-descriptor)]
       (sync/watch channel ref-descriptor !ref)
       (println "No ref found" ref-descriptor)))
   :sync/unwatch
   (fn [{:as context :keys [resolve-ref channel]} ref-descriptor]
     (if-let [!ref (resolve-ref context ref-descriptor)]
       (sync/unwatch channel !ref)
       (println "No ref found" ref-descriptor)))})

;; A `handle` function resolves an mvec to its handler function, then calls the function,
;; passing in the mvec and a context map.

(defn handle-message [context mvec]
  (prn :mvec mvec)
  (if-let [handler ((:handlers context) (mvec 0))]
    (apply handler context (rest mvec))
    (println (str "Handler not found for: " mvec))))

(def DEFAULT-PORT 9060)

#?(:clj
   (defonce !servers (atom {})))

#?(:clj
   (defn run-server [f {:as opts
                        :keys [port]
                        :or {port DEFAULT-PORT}}]
     (when-let [stop (@!servers port)] (stop))
     (swap! !servers assoc port (when f (http/run-server f (assoc opts :port port))))
     (@!servers port)))

#?(:clj
   (defn handle-request [{:as opts :keys [path resolve-ref handlers]}
                         {:as request :keys [uri request-method]}]
     (if (= [request-method uri]
            [:get path])
       (let [channel (atom {})
             context (merge opts
                            {:channel channel
                             :resolve-ref resolve-ref
                             :handlers (merge builtin-handlers handlers)})]
         (http/as-channel request
                          {:init (fn [ch]
                                   (swap! channel assoc
                                          :send (fn [message]
                                                  (if (http/open? ch)
                                                    (http/send! ch (t/pack message))
                                                    (println :sending-message-before-open message)))))
                           :on-open sync/on-open
                           :on-receive (fn [ch message]
                                         (handle-message context (t/unpack message)))
                           :on-close (fn [ch status]
                                       (sync/on-close channel))}))
       (throw (ex-info (str "Unknown request " request-method uri) request)))))

#?(:clj
   (defn serve
     "Serve websocket connections on `port`, with optional handlers

     :on-open    [channel, send-fn]
     :on-message [channel, message]
     :on-close   [channel, status]

     send-fn     [message]  (received by :on-open)"

     [options]
     (run-server (fn [req]
                   (handle-request options req))
                 options)))

#?(:cljs
   (defn connect
     "Connects to websocket server at port/path (insecure, localhost) or url with optional handlers:

     :on-open [socket]
     :on-message [socket, message]
     :on-close [socket]
     "
     [{:as opts :keys [url port path handlers]}]
     (let [!channel (atom {:!last-message (atom nil)})
           send (fn [message]
                  (prn :send message)
                  (let [^js ws (:ws @!channel)]
                    (when (= 1 (.-readyState ws))
                      (.send ws (t/pack message)))))
           _ (swap! !channel assoc :send send)
           context (merge opts {:channel !channel
                                :handlers (merge builtin-handlers handlers)})
           init-ws (fn init-ws []
                     (let [ws (js/WebSocket. (or url (str "ws://localhost:" port path)))]
                       (swap! !channel assoc :ws ws)
                       (doto ws
                         (.addEventListener "open" (fn [_]
                                                     (sync/on-open !channel)))
                         (.addEventListener "close" (fn [_]
                                                      (sync/on-close !channel)
                                                      (js/setTimeout init-ws 1000)))
                         (.addEventListener "message" #(let [message (t/unpack (j/get % :data))]
                                                         (reset! (:!last-message @!channel) message)
                                                         (handle-message context message))))))]
       (init-ws)
       !channel)))


