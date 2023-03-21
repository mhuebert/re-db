(ns re-db.notebooks.tools.websocket
  "Small clojure(script) server(client) websocket API"
  (:require #?(:clj [org.httpkit.server :as http])
            [applied-science.js-interop :as j]
            [re-db.sync.transit :as t]
            [re-db.sync :as sync]))

(def default-options {:pack t/pack
                      :unpack t/unpack
                      :path "/ws"})

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
     (swap! !servers assoc port (when f (http/run-server f (assoc opts :port port))))
     (@!servers port)))

#?(:clj
   (defn handle-request [{:as server-opts
                          :keys [path pack unpack handlers]}
                         {:as request :keys [uri request-method]}]
     (if (= [request-method uri]
            [:get path])
       (let [!ch (atom nil)
             channel {:!ch !ch
                      ::sync/send (fn [message]
                                    (let [ch @!ch]
                                      (if (http/open? ch)
                                        (http/send! ch (pack message))
                                        (println :sending-message-before-open message))))}
             context {:channel channel}]
         (http/as-channel request
                          {:init (fn [ch] (reset! !ch ch))
                           :on-open sync/on-open
                           :on-receive (fn [ch message]
                                         (sync/handle-message handlers context (unpack message)))
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

     [& {:as options}]
     (let [options (merge default-options options)]
       (run-server (fn [req]
                     (handle-request options req))
                   options))))

#?(:cljs
   (defn connect
     "Connects to websocket server, returns a channel.

     :on-open [socket]
     :on-message [socket, message]
     :on-close [socket]
     "
     [& {:as options}]
     (let [{:keys [url port path pack unpack handlers]} (merge default-options options)
           !ws (atom nil)
           channel {:!last-message (atom nil)
                    :ws !ws
                    ::sync/send (fn [message]
                                  (let [^js ws @!ws]
                                    (when (= 1 (.-readyState ws))
                                      (.send ws (pack message)))))}
           context (assoc options :channel channel)
           init-ws (fn init-ws []
                     (let [ws (js/WebSocket. (or url (str "ws://localhost:" port path)))]
                       (reset! !ws ws)
                       (doto ws
                         (.addEventListener "open" (fn [_]
                                                     (sync/on-open channel)))
                         (.addEventListener "close" (fn [_]
                                                      (sync/on-close channel)
                                                      (js/setTimeout init-ws 1000)))
                         (.addEventListener "message" #(let [message (unpack (j/get % :data))]
                                                         (reset! (:!last-message channel) message)
                                                         (sync/handle-message handlers context message))))))]
       (init-ws)
       channel)))


