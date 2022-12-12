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
                        :keys [pack
                               port]
                        :or {port DEFAULT-PORT}}]
     (when-let [stop (@!servers port)] (stop))
     (swap! !servers assoc port (when f (http/run-server f (assoc opts :port port))))
     (@!servers port)))

#?(:clj
   (defn handle-request [{:keys [path
                                 pack
                                 unpack
                                 on-message
                                 on-close
                                 on-open]}
                         {:as request :keys [uri request-method]}]
     (if (= [request-method uri]
            [:get path])
       (http/with-channel request channel*
                          (let [channel (atom {:send (fn [message] (http/send! channel* (pack message)))})]
                            (when on-open
                              (on-open channel (:send channel)))
                            (when on-close
                              (http/on-close channel* (fn [status] (on-close channel status))))
                            (when on-message
                              (http/on-receive channel* (fn [message]
                                                          (on-message channel (unpack message))))))*)
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
              pack
              unpack
              on-open
              on-message
              on-close]}]
     (def ^js ws
       (let [^js ws (js/WebSocket. url)
             !last-message (atom nil)
             !ws (atom ws)
             !channel
             (atom {:pack pack
                    :unpack unpack
                    :send (fn [message] (.send ^js @!ws (pack message)))
                    :ws ws
                    :!last-message !last-message})]
         (cond-> ws
                 on-open
                 (doto (.addEventListener "open" #(on-open !channel)))
                 on-close
                 (doto (.addEventListener "close" #(on-close !channel)))
                 on-message
                 (doto (.addEventListener "message" #(let [message (unpack (j/get % :data))]
                                                       (reset! !last-message message)
                                                       (on-message !channel message)))))
         !channel))))

(defn send! [channel message]
  ((:send @channel) message))


