(ns cyrus-ui-oauth2.core
  (:require [clj-http.client :as http]
            [clojure.spec.alpha :as s]
            [ring.util.codec :as codec]
            [ring.util.request :as req]
            [ring.util.response :as resp]
            [crypto.random :as random]
            [clojure.string :as str]
            [cemerick.friend :as friend])
  (:import (java.net URI)
           (java.security MessageDigest)
           (clojure.lang ExceptionInfo)))


(defn- scopes [profile]
  (str/join " " (map name (:scopes profile))))


(defn- resolve-value-or-getter [value-or-fn]
  (if (ifn? value-or-fn)
    (value-or-fn)
    value-or-fn))


(defn- get-authorize-url [profile redirect-url state]
  (str (:authorize-url profile)
       (if (.contains ^String (:authorize-url profile) "?") "&" "?")
       (codec/form-encode {:response_type "code"
                           :client_id     (resolve-value-or-getter (:client-id profile))
                           :redirect_uri  redirect-url
                           :scope         (scopes profile)
                           :state         state})))


(defn- get-access-token [{:keys [access-token-url basic-auth? redirect-endpoint]
                          :or   {basic-auth? false} :as profile}
                         redirect-url code]
  (let [client-id     (resolve-value-or-getter (:client-id profile))
        client-secret (resolve-value-or-getter (:client-secret profile))]
    (:body
      (http/post access-token-url
                 (merge {:accept      :json
                         :as          :json
                         :form-params (merge {:grant_type   "authorization_code"
                                              :code         code
                                              :redirect_uri redirect-url}
                                             (when-not basic-auth?
                                               {:client_id     client-id
                                                :client_secret client-secret}))}
                        (when basic-auth?
                          {:basic-auth [client-id client-secret]}))))))


(defn- sha256 [x]
  (let [acc (doto (MessageDigest/getInstance "SHA-256")
              (.update (.getBytes x)))]
    (->> acc
         (.digest)
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str))))


(defn- redirect-with-session [url session]
  (-> (resp/redirect url)
      (assoc :session session)))


(defn- get-redirect-url [{:keys [external-url redirect-endpoint]} request]
  (-> (or external-url (req/request-url request))
      (URI/create)
      (.resolve redirect-endpoint)
      str))


(defn random-state []
  (-> (random/base64 10) (str/replace "+" "-") (str/replace "/" "_")))


(defn oauth2-workflow [{:as profile :keys [external-url login-endpoint logout-endpoint redirect-endpoint tokeninfo-url default-landing-endpoint]}]
  (fn [{:as request :keys [uri session params]}]
    (let [{:keys [code error state]} params
          redirect-url (get-redirect-url profile request)]
      (condp = uri

        login-endpoint
        (let [state (random-state)]
          (-> (redirect-with-session (get-authorize-url profile redirect-url state) session)
              (assoc-in [:session ::state] state)))

        redirect-endpoint
        (if-not (= state (::state session))
          ;; If this is happening, check that you have wrap-session and wrap-params middleware
          {:status 400 :body "CSRF token does not match session state"}
          (cond
            code (let [raw-tokeninfo (get-access-token profile redirect-url code)
                       token         (:access_token raw-tokeninfo)
                       short-token   (if (<= 64 (count token))
                                       (sha256 token)
                                       token)]
                   (with-meta
                     (merge {:identity      short-token
                             ::access-token token
                             :roles         #{}}
                            (when-not tokeninfo-url
                              {::tokeninfo raw-tokeninfo}))
                     {::friend/workflow          :oauth2
                      ::friend/redirect-on-auth? true
                      :type                      ::friend/auth}))
            error {:status 401 :body error}))

        logout-endpoint
        (friend/logout* (redirect-with-session default-landing-endpoint session))

        ;; default
        nil))))


(defn- get-tokeninfo [tokeninfo-url token]
  (:body (http/get tokeninfo-url {:oauth-token token :as :json})))


(defn enrich-request-tokeninfo [request tokeninfo-url]
  (if-let [auth (friend/current-authentication request)]
    (if-not tokeninfo-url
      (assoc request ::tokeninfo (::tokeninfo auth))
      (let [token     (-> auth ::access-token)
            tokeninfo (try
                        (get-tokeninfo tokeninfo-url token)
                        (catch ExceptionInfo _
                          ::invalid))]
        (if (= tokeninfo ::invalid)
          (friend/logout* request)
          (assoc request ::tokeninfo tokeninfo))))
    request))


(defn wrap-tokeninfo [handler tokeninfo-url]
  (fn [request]
    (handler (enrich-request-tokeninfo request tokeninfo-url))))


(defn wrap-ui-oauth2 [handler profile]
  (let [{:keys [allow-anon? login-endpoint default-landing-endpoint tokeninfo-url]} profile]
    (-> handler
        (friend/authenticate (merge (when default-landing-endpoint
                                      {:default-landing-uri default-landing-endpoint})
                                    {:allow-anon? allow-anon?
                                     :login-uri   login-endpoint
                                     :workflows   [(oauth2-workflow profile)]}))
        (wrap-tokeninfo tokeninfo-url))))


(s/def ::string-or-getter (s/or :value string? :getter ifn?))

(s/def ::authorize-url string?)
(s/def ::access-token-url string?)
(s/def ::client-id ::string-or-getter)
(s/def ::client-secret ::string-or-getter)
(s/def ::redirect-endpoint string?)
(s/def ::login-endpoint string?)
(s/def ::default-landing-endpoint (s/nilable string?))
(s/def ::allow-anon? (s/nilable boolean?))
(s/def ::tokeninfo-url (s/nilable string?))
(s/def ::logout-endpoint (s/nilable string?))
(s/def ::external-url (s/nilable string?))
(s/def ::scopes (s/nilable (s/coll-of string?)))

(s/def ::profile (s/keys :req-un [::authorize-url ::access-token-url ::client-id ::client-secret ::redirect-endpoint ::login-endpoint]
                         :opt-un [::external-url ::scopes ::allow-anon? ::tokeninfo-url ::logout-endpoint ::default-landing-endpoint]))

(s/fdef wrap-ui-oauth2
        :args (s/cat :handler fn? :profile ::profile))
