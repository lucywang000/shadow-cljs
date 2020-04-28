(ns shadow.cljs.devtools.client.browser
  (:require
    [cljs.reader :as reader]
    [clojure.string :as str]
    [goog.dom :as gdom]
    [goog.dom.classlist :as classlist]
    [goog.userAgent.product :as product]
    [goog.Uri]
    [goog.net.XhrIo :as xhr]
    [shadow.cljs.devtools.client.env :as env]
    [shadow.cljs.devtools.client.console]
    [shadow.cljs.devtools.client.hud :as hud]
    [clojure.set :as set]))

(defonce repl-ns-ref (atom nil))

(defonce socket-ref (volatile! nil))

(defn devtools-msg [msg & args]
  (if (seq env/log-style)
    (js/console.log.apply js/console (into-array (into [(str "%c\uD83E\uDC36 shadow-cljs: " msg) env/log-style] args)))
    (js/console.log.apply js/console (into-array (into [(str "shadow-cljs: " msg)] args)))))

(defn ws-msg [msg]
  (if-let [s @socket-ref]
    (.send s (pr-str msg))
    (js/console.warn "WEBSOCKET NOT CONNECTED" (pr-str msg))))

(defn script-eval [code]
  (js/goog.globalEval code))

(defn do-js-load [sources]
  (doseq [{:keys [resource-id output-name resource-name js] :as src} sources]
    ;; should really stop using this and rather maintain our own record
    ;; but without this hot-reload will reload shadow-js files with each cycle
    ;; since they don't set it
    (js/$CLJS.SHADOW_ENV.setLoaded output-name)

    (devtools-msg "load JS" resource-name)
    (env/before-load-src src)
    (try
      (script-eval (str js "\n//# sourceURL=" resource-name))
      (catch :default e
        (js/console.error (str "Failed to load " resource-name) e)
        (throw (js/Error. (str "Failed to load " resource-name ": " (.-message e))))))))

(defn do-js-reload [msg sources complete-fn failure-fn]
  (env/do-js-reload
    (assoc msg
      :log-missing-fn
      ;; FIXME: this gets noisy when using web-workers and either main or the workers not having certain code loaded
      ;; should properly filter hook-fns and only attempt to call those that actually apply
      ;; but thats a bit of work since we don't currently track the namespaces that are loaded.
      (fn [fn-sym]
        #_ (devtools-msg (str "can't find fn " fn-sym)))
      :log-call-async
      (fn [fn-sym]
        (devtools-msg (str "call async " fn-sym)))
      :log-call
      (fn [fn-sym]
        (devtools-msg (str "call " fn-sym))))
    #(do-js-load sources)
    complete-fn
    failure-fn))

(defn do-js-requires
  "when (require '[\"some-str\" :as x]) is done at the REPL we need to manually call the shadow.js.require for it
   since the file only adds the shadow$provide. only need to do this for shadow-js."
  [js-requires]
  (doseq [js-ns js-requires]
    (let [require-str (str "var " js-ns " = shadow.js.require(\"" js-ns "\");")]
      (script-eval require-str))))

(defn load-sources [sources callback]
  (if (empty? sources)
    (callback [])
    (xhr/send
      (env/files-url)
      (fn [res]
        (this-as ^goog req
          (let [content
                (-> req
                    (.getResponseText)
                    (reader/read-string))]
            (callback content)
            )))
      "POST"
      (pr-str {:client :browser
               :sources (into [] (map :resource-id) sources)})
      #js {"content-type" "application/edn; charset=utf-8"})))

(defn handle-build-complete [{:keys [info reload-info] :as msg}]
  (let [warnings
        (->> (for [{:keys [resource-name warnings] :as src} (:sources info)
                   :when (not (:from-jar src))
                   warning warnings]
               (assoc warning :resource-name resource-name))
             (distinct)
             (into []))]

    (doseq [{:keys [msg line column resource-name] :as w} warnings]
      (js/console.warn (str "BUILD-WARNING in " resource-name " at [" line ":" column "]\n\t" msg)))

    (if-not env/autoload
      (hud/load-end-success)
      (when (or (empty? warnings) env/ignore-warnings)
        (let [sources-to-get
              (env/filter-reload-sources info reload-info)]

          (if-not (seq sources-to-get)
            (hud/load-end-success)
            (do (when-not (seq (get-in msg [:reload-info :after-load]))
                  (devtools-msg "reloading code but no :after-load hooks are configured!"
                    "https://shadow-cljs.github.io/docs/UsersGuide.html#_lifecycle_hooks"))
                (load-sources sources-to-get #(do-js-reload msg % hud/load-end-success hud/load-failure)))
            ))))))

;; capture this once because the path may change via pushState
(def ^goog page-load-uri
  (when js/goog.global.document
    (goog.Uri/parse js/document.location.href)))

(defn match-paths [old new]
  (if (= "file" (.getScheme page-load-uri))
    ;; new is always an absolute path, strip first /
    ;; FIXME: assuming that old is always relative
    (let [rel-new (subs new 1)]
      (when (or (= old rel-new)
                (str/starts-with? old (str rel-new "?")))
        rel-new))
    ;; special handling for browsers including relative css
    (let [^goog node-uri (goog.Uri/parse old)
          node-uri-resolved (.resolve page-load-uri node-uri)
          node-abs (.getPath ^goog node-uri-resolved)]

      (and (or (= (.hasSameDomainAs page-load-uri node-uri))
               (not (.hasDomain node-uri)))
           (= node-abs new)
           new))))

(defn handle-asset-watch [{:keys [updates] :as msg}]
  (doseq [path updates
          ;; FIXME: could support images?
          :when (str/ends-with? path "css")]
    (doseq [node (array-seq (js/document.querySelectorAll "link[rel=\"stylesheet\"]"))
            :let [path-match (match-paths (.getAttribute node "href") path)]
            :when path-match]

      (let [new-link
            (doto (.cloneNode node true)
              (.setAttribute "href" (str path-match "?r=" (rand))))]

        (classlist/add js/document.body "no-anim")
        (devtools-msg "load CSS" path-match)
        (gdom/insertSiblingAfter new-link node)
        (gdom/removeNode node)
        (classlist/remove js/document.body "no-anim")))))

;; from https://github.com/clojure/clojurescript/blob/master/src/main/cljs/clojure/browser/repl.cljs
;; I don't want to pull in all its other dependencies just for this function
(defn get-ua-product []
  (cond
    product/SAFARI :safari
    product/CHROME :chrome
    product/FIREFOX :firefox
    product/IE :ie))

(defn get-asset-root []
  (let [loc (js/goog.Uri. js/document.location.href)
        cbp (js/goog.Uri. js/CLOSURE_BASE_PATH)
        s (.toString (.resolve loc cbp))]
    ;; FIXME: stacktrace starts with file:/// but resolve returns file:/
    ;; how does this look on windows?
    (str/replace s #"^file:/" "file:///")
    ))

(defn repl-error [e]
  (js/console.error "repl/invoke error" e)
  (-> (env/repl-error e)
      (assoc :ua-product (get-ua-product)
             :asset-root (get-asset-root))))

(defn global-eval [js]
  (if (not= "undefined" (js* "typeof(module)"))
    ;; don't eval in the global scope in case of :npm-module builds running in webpack
    (js/eval js)
    ;; hack to force eval in global scope
    ;; goog.globalEval doesn't have a return value so can't use that for REPL invokes
    (js* "(0,eval)(~{});" js)))

(defn repl-invoke [{:keys [id js]}]
  (let [result (env/repl-call #(global-eval js) repl-error)]
    (-> result
        (assoc :id id)
        (ws-msg))))

(defn repl-require [{:keys [id sources reload-namespaces js-requires] :as msg} done]
  (let [sources-to-load
        (->> sources
             (remove (fn [{:keys [provides] :as src}]
                       (and (env/src-is-loaded? src)
                            (not (some reload-namespaces provides)))))
             (into []))]

    (load-sources
      sources-to-load
      (fn [sources]
        (try
          (do-js-load sources)
          (when (seq js-requires)
            (do-js-requires js-requires))
          (ws-msg {:type :repl/require-complete :id id})
          (catch :default e
            (ws-msg {:type :repl/require-error :id id :error (.-message e)}))
          (finally
            (done)))))))

(defn repl-init [{:keys [repl-state id]} done]
  (load-sources
    ;; maybe need to load some missing files to init REPL
    (->> (:repl-sources repl-state)
         (remove env/src-is-loaded?)
         (into []))
    (fn [sources]
      (do-js-load sources)
      (ws-msg {:type :repl/init-complete :id id})
      (devtools-msg "REPL session start successful")
      (done))))

(defn repl-set-ns [{:keys [id ns]}]
  (ws-msg {:type :repl/set-ns-complete :id id :ns ns}))

(def close-reason-ref (volatile! nil))
(def stale-client-detected (volatile! false))

;; FIXME: core.async-ify this
(defn handle-message [{:keys [type] :as msg} done]
  ;; (js/console.log "ws-msg" msg)
  (hud/connection-error-clear!)
  (case type
    :asset-watch
    (handle-asset-watch msg)

    :repl/invoke
    (repl-invoke msg)

    :repl/require
    (repl-require msg done)

    :repl/set-ns
    (repl-set-ns msg)

    :repl/init
    (repl-init msg done)

    :repl/session-start
    (repl-init msg done)

    :repl/ping
    (ws-msg {:type :repl/pong :time-server (:time-server msg) :time-runtime (js/Date.now)})

    :build-complete
    (do (hud/hud-warnings msg)
        (handle-build-complete msg))

    :build-failure
    (do (hud/load-end)
        (hud/hud-error msg))

    :build-init
    (hud/hud-warnings msg)

    :build-start
    (do (hud/hud-hide)
        (hud/load-start))

    :pong
    nil

    :client/stale
    (do (vreset! stale-client-detected true)
        (vreset! close-reason-ref "Stale Client! You are not using the latest compilation output!"))

    :client/no-worker
    (do (vreset! stale-client-detected true)
        (vreset! close-reason-ref (str "watch for build \"" env/build-id "\" not running")))

    :custom-msg
    (env/publish! (:payload msg))

    ;; default
    :ignored)

  (when-not (contains? env/async-ops type)
    (done)))

(defn compile [text callback]
  (xhr/send
    (str "http" (when env/ssl "s") "://" env/server-host ":" env/server-port "/worker/compile/" env/build-id "/" env/proc-id "/browser")
    (fn [res]
      (this-as ^goog req
        (let [actions
              (-> req
                  (.getResponseText)
                  (reader/read-string))]
          (when callback
            (callback actions)))))
    "POST"
    (pr-str {:input text})
    #js {"content-type" "application/edn; charset=utf-8"}))

;; :init
;; :connecting
;; :connected
(defonce ws-status (volatile! :init))

(declare ws-connect-impl)

(defn ws-connect []
  (when (= (@ws-status :init))
    (ws-connect-impl)))

(defn maybe-reconnect []
  (when (and (not @stale-client-detected)
             (not= @ws-status :init))
    (vreset! ws-status :init)
    (js/setTimeout ws-connect 3000)))

(defn ws-connect-impl []
  (vreset! ws-status :connecting)
  (try
    (let [print-fn
          cljs.core/*print-fn*

          ws-url
          (env/ws-url :browser)

          socket
          (js/WebSocket. ws-url)]

      (vreset! socket-ref socket)

      (set! (.-onmessage socket)
        (fn [e]
          (env/process-ws-msg (. e -data) handle-message)
          ))

      (set! (.-onopen socket)
        (fn [e]
          (vreset! ws-status :connected)
          (hud/connection-error-clear!)
          (vreset! close-reason-ref nil)
          ;; :module-format :js already patches provide
          (when (= "goog" env/module-format)
            ;; patch away the already declared exception
            (set! (.-provide js/goog) js/goog.constructNamespace_))

          (env/set-print-fns! ws-msg)

          (devtools-msg "WebSocket connected!")
          ))

      (set! (.-onclose socket)
        (fn [e]
          ;; not a big fan of reconnecting automatically since a disconnect
          ;; may signal a change of config, safer to just reload the page
          (devtools-msg "WebSocket disconnected!")
          (hud/connection-error (or @close-reason-ref "Connection closed!"))
          (vreset! socket-ref nil)
          (env/reset-print-fns!)
          (maybe-reconnect)
          ))

      (set! (.-onerror socket)
        (fn [e]
          (hud/connection-error "Connection failed!")
          (maybe-reconnect)
          (devtools-msg "websocket error" e))))
    (catch :default e
      (devtools-msg "WebSocket setup failed" e))))

(when ^boolean env/enabled
  ;; disconnect an already connected socket, happens if this file is reloaded
  ;; pretty much only for me while working on this file
  (when-let [s @socket-ref]
    (devtools-msg "connection reset!")
    (set! (.-onclose s) (fn [e]))
    (.close s)
    (vreset! socket-ref nil))

  ;; for /browser-repl in case the page is reloaded
  ;; otherwise the browser seems to still have the websocket open
  ;; when doing the reload
  (when js/goog.global.window
    (js/window.addEventListener "beforeunload"
      (fn []
        (when-let [s @socket-ref]
          (.close s)))))

  ;; async connect so other stuff while loading runs first
  (if (and js/goog.global.document (= "loading" js/goog.global.document.readyState))
    (js/window.addEventListener "DOMContentLoaded" ws-connect)
    (js/setTimeout ws-connect 10)))
