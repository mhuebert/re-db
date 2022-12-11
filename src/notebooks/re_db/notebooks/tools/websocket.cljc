(ns re-db.notebooks.tools.websocket
  "Small clojure(script) server(client) websocket API"
  (:require #?(:clj [org.httpkit.server :as http])
            [applied-science.js-interop :as j]))

;; A full-stack websocket API, which can
;; 1. run a server which accepts websocket connections,
;; 2. connect to this server from a browser.

(def DEFAULT-PORT 9060)

#?(:clj
   (defonce !servers (atom {})))

#?(:clj
   (defn run-server [f {:as opts
                        :keys [port]
                        :or {port DEFAULT-PORT}}]
     (when-let [stop (@!servers port)] (stop))
     (swap! !servers assoc port (when f (http/run-server f (assoc opts :port port))))))

#?(:clj
   (defn handle-request [{:keys [path
                                 on-message
                                 on-close
                                 on-open]}
                         {:as request :keys [uri request-method]}]
     (if (= [request-method uri]
            [:get path])
       (http/with-channel request channel
                          (when on-open
                            (on-open channel (fn send! [message] (http/send! channel message))))
                          (when on-close
                            (http/on-close channel (fn [status] (on-close channel status))))
                          (when on-message
                            (http/on-receive channel (fn [message] (on-message channel message)))))
       (throw (ex-info (str "Unknown request " request-method uri) request)))))

#?(:clj
   (defn serve
     "Serve websocket connections on `port`, with optional handlers

     :on-open    [channel, send-fn]
     :on-message [channel, message]
     :on-close   [channel, status]

     send-fn     [message]  (received by :on-open)"

     [options]
     (run-server (partial handle-request options) options)))

#?(:cljs
   (defn connect
     "Connects to websocket server at port/path (insecure, localhost) or url with optional handlers:

     :on-open [socket]
     :on-message [socket, message]
     :on-close [socket]
     "
     [{:keys [url
              on-open
              on-message
              on-close]}]
     (def ^js ws
       (let [^js ws (js/WebSocket. url)]
         (cond-> ws
                 on-open
                 (doto (.addEventListener "open" #(on-open ws)))
                 on-close
                 (doto (.addEventListener "close" #(on-close ws)))
                 on-message
                 (doto (.addEventListener "message" #(on-message ws (j/get % :data)))))))))

(defn send! [channel message]
  #?(:clj  (http/send! channel message)
     :cljs (.send ^js channel message)))


