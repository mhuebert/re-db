(ns re-db.notebooks.websocket
  "Small clojure(script) server(client) websocket API"
  (:require #?(:clj [org.httpkit.server :as http])
            [applied-science.js-interop :as j]))

#?(:clj
   (defonce !servers (atom {})))

#?(:clj
   (defn run-server [f {:as opts
                        :keys [port]
                        :or {port 9060}}]
     (when-let [stop (@!servers port)] (stop))
     (swap! !servers assoc port (when f (http/run-server f (assoc opts :port port))))))

#?(:clj
   (defn handler [{:keys [on-message
                          on-close
                          on-open]}
                  {:as request :keys [uri request-method]}]
     (case [request-method uri]
       [:get "/ws"] (http/with-channel request channel
                                       (when on-open
                                         (on-open channel (fn send! [message] (http/send! channel message))))
                                       (when on-close
                                         (http/on-close channel (fn [status] (on-close channel status))))
                                       (when on-message
                                         (http/on-receive channel (fn [message] (on-message channel message))))))))

#?(:clj
   (defn serve
     "Serve websocket connections on `port`, with optional handlers

     :on-open    [channel, send-fn]
     :on-message [channel, message]
     :on-close   [channel, status]

     send-fn     [message]  (received by :on-open)"

     [options]
     (run-server (partial handler options) options)))

#?(:cljs
   (defn connect
     "Connects to websocket server at port/path (insecure, localhost) or url with optional handlers:

     :on-open [socket]
     :on-message [socket, message]
     :on-close [socket]
     "
     [{:keys [port
              path
              url
              on-open
              on-message
              on-close]
       :or {path "ws"}}]
     (def ^js ws
       (let [^js ws (js/WebSocket. (or url (str "ws://localhost:" port "/" path)))]
         (cond-> ws
                 on-open
                 (doto (.addEventListener "open" #(on-open ws)))
                 on-close
                 (doto (.addEventListener "close" #(on-close ws)))
                 on-message
                 (doto (.addEventListener "message" #(on-message ws (j/get % :data)))))))))

#?(:clj
   (defn send! [channel message] (http/send! channel message))
   :cljs
   (defn send! [^js socket message] (.send socket message)))


