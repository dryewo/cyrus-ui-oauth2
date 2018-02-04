(ns cyrus-ui-oauth2.core-test
  (:require [clojure.test :refer :all]
            [clojure.spec.test.alpha :as st]
            [cyrus-ui-oauth2.core :refer :all :as uo2]
            [whitepages.expect-call :refer [expect-call]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.mock.request :as mock]
            [ring.middleware.session.memory :as memory]
            [juxt.iota :refer [given]]
            [cemerick.friend :as friend]
            [clj-http.client :as http]))


(st/instrument)


(defn get-default [request]
  {:status 200 :body request})


(defn make-test-handler [profile *sessions]
  (-> get-default
      (wrap-ui-oauth2 profile)
      ;(wrap-ppr)
      (wrap-defaults (-> site-defaults
                         (assoc-in [:security :anti-forgery] false)
                         (assoc-in [:session :cookie-attrs :same-site] :lax)
                         (assoc-in [:session :store] (memory/memory-store (or *sessions (atom {}))))))))


(def test-oauth2-profile-1 {:authorize-url     "authorize-url"
                            :access-token-url  "access-token-url"
                            :client-id         "client-id"
                            :client-secret     "client-secret"
                            :redirect-endpoint "/callback"
                            :login-endpoint    "/login"})


(deftest unauthenticated-redirect
  (testing "When request comes and has no authentication, it's redirected to /login"
    (let [handler (make-test-handler test-oauth2-profile-1 nil)]
      (given (handler (mock/request :get "/"))
        :status := 302
        :body := ""
        [:headers "Location"] := "http://localhost/login")))

  (testing "When anonymous access is allowed, return the content"
    (let [handler (make-test-handler (merge test-oauth2-profile-1 {:allow-anon? true}) nil)
          res     (handler (mock/request :get "/"))]
      (given res
        :status := 200)
      res)))


(deftest login-redirect
  (testing "When request comes to /login, it's redirected to authorize-url, state is set"
    (let [*sessions (atom {})
          handler   (make-test-handler test-oauth2-profile-1 *sessions)
          res       (with-redefs [random-state (constantly "random-state")]
                      (handler (mock/request :get "/login")))]
      (given res
        :status := 302
        [:headers "Location"] := "http://localhost/authorize-url?response_type=code&client_id=client-id&redirect_uri=http%3A%2F%2Flocalhost%2Fcallback&scope=&state=random-state")
      (is (= {::uo2/state "random-state"} (-> @*sessions first val)))
      res)))


(deftest callback-redirect
  (testing "When request comes to /ui/callback and has code, a request is made to access-token-url and then a redirect to the original url"
    (let [*sessions (atom {"session-key" {::uo2/state "random-state"}})
          handler   (make-test-handler test-oauth2-profile-1 *sessions)
          request   (-> (mock/request :get "/callback?code=very-secret-code&state=random-state")
                        (mock/header "cookie" "ring-session=session-key"))
          res       (expect-call [(http/post ["access-token-url" params]
                                             (is (= (:form-params params) {:client_id     "client-id"
                                                                           :client_secret "client-secret"
                                                                           :code          "very-secret-code"
                                                                           :grant_type    "authorization_code"
                                                                           :redirect_uri  "http://localhost/callback"}))
                                             {:status 200
                                              :body   {:access_token "access-token"}})]
                      (handler request))]
      (given @*sessions
        ["session-key" ::friend/identity] := {:authentications {"access-token" {::uo2/access-token "access-token"
                                                                                ::uo2/tokeninfo    {:access_token "access-token"}
                                                                                :identity          "access-token"
                                                                                :roles             #{}}}
                                              :current         "access-token"})
      (given res
        :status := 303
        [:headers "Location"] := "http://localhost/")
      [res *sessions]))

  (testing ":external-url is respected when set"
    (let [*sessions (atom {"session-key" {::uo2/state "random-state"}})
          handler   (make-test-handler (assoc test-oauth2-profile-1 :external-url "https://example.com") *sessions)
          request   (-> (mock/request :get "/callback?code=very-secret-code&state=random-state")
                        (mock/header "cookie" "ring-session=session-key"))]
      (expect-call [(http/post ["access-token-url" params]
                               (is (= (:form-params params) {:client_id     "client-id"
                                                             :client_secret "client-secret"
                                                             :code          "very-secret-code"
                                                             :grant_type    "authorization_code"
                                                             :redirect_uri  "https://example.com/callback"}))
                               {:status 200
                                :body   {:access_token "access-token"}})]
        (handler request)))))


(deftest authenticated-request
  (testing "For an authenticated request return 200"
    (let [*sessions (atom {"session-key" {::friend/identity {:authentications {"access-token" {::uo2/access-token "access-token"
                                                                                               ::uo2/tokeninfo    {:access_token "access-token"}
                                                                                               :identity          "access-token"
                                                                                               :roles             #{}}}
                                                             :current         "access-token"}}})
          handler   (make-test-handler test-oauth2-profile-1 *sessions)
          request   (-> (mock/request :get "/")
                        (mock/header "cookie" "ring-session=session-key"))
          res       (handler request)]
      (given res
        :status := 200
        [:body ::uo2/tokeninfo] := {:access_token "access-token"}))))


(deftest test-wrap-default-x-forwarded-host
  (are [?in-headers ?out-headers]
    (is (= ?out-headers (:headers ((wrap-default-x-forwarded-host identity) {:headers ?in-headers}))))
    ;; Handles nil
    nil nil
    ;; When "host" is not set, keeps "x-forwarded-host"
    {"x-forwarded-host" "external-host"}
    {"x-forwarded-host" "external-host"}
    ;; When "host" is nil, does not override "x-forwarded-host"
    {"host" nil "x-forwarded-host" "external-host"}
    {"host" nil "x-forwarded-host" "external-host"}
    ;; "host" does not override "x-forwarded-host" when it's already there
    {"host" "internal-host" "x-forwarded-host" "external-host"}
    {"host" "internal-host" "x-forwarded-host" "external-host"}
    ;; Provides "x-forwarded-host" when it's missing
    {"host" "external-host"}
    {"host" "external-host" "x-forwarded-host" "external-host"}
    ;; Removes port number from "host" when providing "x-forwarded-host"
    {"host" "external-host:8080"}
    {"host" "external-host:8080" "x-forwarded-host" "external-host"}))
