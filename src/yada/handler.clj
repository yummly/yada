;; Copyright © 2015, JUXT LTD.

(ns yada.handler
  (:require
   [bidi.bidi :as bidi]
   [bidi.ring :as br]
   [clojure.tools.logging :refer [errorf debugf infof]]
   [manifold.deferred :as d]
   [schema.core :as s]
   [yada.body :as body]
   [clojure.pprint :refer [pprint]]
   [yada.media-type :as mt]
   [yada.charset :as charset]
   [yada.security :as sec]
   [yada.interceptors :as i]
   [yada.protocols :as p]
   [yada.methods :as methods]
   [yada.representation :as rep]
   [yada.response :refer [->Response]]
   [yada.resource :as resource]
   [yada.schema :refer [resource-coercer] :as ys])
  (:import [yada.resource Resource]))

(declare new-handler)

(defn make-context []
  {:response (->Response)})

(defn error-data
  [e]
  (cond
    (instance? clojure.lang.ExceptionInfo e) (ex-data e)
    (instance? java.lang.Throwable e) nil
    :else e))

(defn default-error-handler [e]
  (let [data (error-data e)]
    (when-not (and (:status data) (< (:status data) 500))
      (when (instance? java.lang.Throwable e)
        (errorf e "Internal Error %s" (or (some-> data :status str) "")))
      (when data (errorf "ex-data: %s" data)))))

;; Response


(defn allowed-methods [resource]
  (let [methods (set (keys (:methods resource)))]
    (cond-> methods
      (some #{:get} methods) (conj :head)
      true (conj :options))))

(defn- handle-request-with-maybe-subresources [ctx]
  (let [resource (:resource ctx)
        error-handler default-error-handler]

    (if
        ;; If the resource provies subresources, call its subresource
        ;; function.  However, if the resource declares it requires
        ;; path-info, only call this if path-info exists, otherwise
        ;; call the parent resource as normal.
        (and (:subresource resource)
             (or (:path-info (:request ctx))
                 (not (:path-info? resource))))

        (let [subresourcefn (:subresource resource)]
          ;; Subresource
          (let [subresource (subresourcefn ctx)
                handler
                (new-handler
                 {:id (get resource :id (java.util.UUID/randomUUID))
                  :parent resource
                  :resource subresource
                  :allowed-methods (allowed-methods subresource)
                  :known-methods (:known-methods ctx)
                  ;; TODO: Could/should subresources, which are dynamic, be able
                  ;; to modify the interceptor-chain?
                  :interceptor-chain (-> ctx :interceptor-chain)})]
          
            (handle-request-with-maybe-subresources
             (-> ctx
                 (merge handler)
                 (dissoc :base)))))

        ;; Normal resources
        (->
         (apply d/chain ctx (:interceptor-chain ctx))

         (d/catch
             clojure.lang.ExceptionInfo
             (fn [e]
               (error-handler e)
               (let [data (error-data e)]
                 (let [status (or (:status data) 500)
                       rep (rep/select-best-representation
                            (:request ctx)
                            ;; TODO: Don't do this! coerce!!
                            (rep/representation-seq
                             (rep/coerce-representations
                              ;; Possibly in future it will be possible
                              ;; to support more media-types to render
                              ;; errors, including image and video
                              ;; formats.

                              [{:media-type #{"application/json"
                                              "application/json;pretty=true;q=0.96"
                                              "text/plain;q=0.9"
                                              "text/html;q=0.8"
                                              "application/edn;q=0.6"
                                              "application/edn;pretty=true;q=0.5"}
                                :charset charset/platform-charsets}])))]

                   ;; TODO: Custom error handlers

                   (d/chain
                    (cond-> ctx
                      ;; true (merge (select-keys ctx [:id :request :method]))
                      status (assoc-in [:response :status] status)
                      (:headers data) (assoc-in [:response :headers] (:headers data))

                      (not (:body data))
                      ((fn [ctx]
                         (let [b (body/to-body (body/render-error status e rep ctx) rep)]
                           (-> ctx
                               (assoc-in [:response :body] b)
                               (assoc-in [:response :headers "content-length"] (str (body/content-length b)))))))

                      rep (assoc-in [:response :produces] rep))
                    sec/access-control-headers
                    i/create-response)))))))))

(defn- handle-request
  "Handle Ring request"
  [handler request match-context]
  (let [method (:request-method request)
        method-wrapper (get (:known-methods handler) method)
        id (java.util.UUID/randomUUID)]
    (handle-request-with-maybe-subresources
     ;; TODO: Possibly we should merge the request-specific details
     ;; with the handler, and remove the explicit distinction between
     ;; the handler and request.

     ;; TODO: Possibly no real need for the convenient
     ;; method-wrapper. Perhaps better to use yada.context to access
     ;; this structure.
     (merge (make-context)
            handler
            {:request-id id
             :request request
             :method method
             :method-wrapper method-wrapper}))))

(defrecord Handler []
  clojure.lang.IFn
  (invoke [this req]
    (handle-request this req (make-context)))

  p/ResourceCoercion
  (as-resource [h]
    (resource-coercer
     {:produces #{"text/html"
                  "application/edn"
                  "application/json"
                  "application/edn;pretty=true"
                  "application/json;pretty=true"}
      :methods {:get (fn [ctx] (into {} h))}}))

  bidi/Matched
  (resolve-handler [this m]
    ;; If we represent a collection of resources, let's match and retain
    ;; the remainder which we place into the request as :path-info (see
    ;; below).
    (if (-> this :resource :path-info?)
      (assoc m :handler this)
      (bidi/succeed this m)))

  (unresolve-handler [this m]
    (when
        (or (= this (:handler m))
            (when-let [id (:id this)] (= id (:handler m))))
        ""))

  br/Ring
  (request [this req match-context]
    (handle-request
     this
     (if (and (-> this :resource :path-info?)
              (not-empty (:remainder match-context)))
         (assoc req :path-info (:remainder match-context))
       req)
     (merge (make-context) match-context))))

(s/defn new-handler [model :- ys/HandlerModel]
  (map->Handler model))


(def default-interceptor-chain
  [i/available?
   i/known-method?
   i/uri-too-long?
   i/TRACE
   i/method-allowed?
   i/parse-parameters
   sec/verify ; step 1
   i/get-properties ; step 2
   sec/authorize ; steps 3,4 and 5
   i/process-request-body
   i/check-modification-time
   i/select-representation
   ;; if-match and if-none-match computes the etag of the selected
   ;; representations, so needs to be run after select-representation
   ;; - TODO: Specify dependencies as metadata so we can validate any
   ;; given interceptor chain
   i/if-match
   i/if-none-match
   i/invoke-method
   i/get-new-properties
   i/compute-etag
   sec/access-control-headers
   i/create-response])

;; We also want resources to be able to be used in bidi routes without
;; having to create yada handlers. This isn't really necessary but a
;; useful convenience to reduce verbosity.

(extend-type Resource
  bidi/Matched
  (resolve-handler [resource m]
    (if (:path-info? resource)
      (assoc m :handler resource)
      (bidi/succeed resource m)))

  (unresolve-handler [resource m]
    (when
        (or (= resource (:handler m))
            (when-let [id (:id resource)] (= id (:handler m))))
        ""))

  br/Ring
  (request [resource req match-context]
    (handle-request
     (new-handler
      (merge
       {:id (get resource :id (java.util.UUID/randomUUID))
        :resource resource
        :allowed-methods (allowed-methods resource)
        :known-methods (methods/known-methods)
        ;; TODO: interceptor chain should be defined in the resource itself
        :interceptor-chain default-interceptor-chain}))

     (if (and (:path-info? resource)
              (not-empty (:remainder match-context)))
       (assoc req :path-info (:remainder match-context))
       req)
     (merge (make-context) match-context))))
