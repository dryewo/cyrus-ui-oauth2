# cyrus-ui-oauth2
[![Build Status](https://travis-ci.org/dryewo/cyrus-ui-oauth2.svg?branch=master)](https://travis-ci.org/dryewo/cyrus-ui-oauth2)
[![codecov](https://codecov.io/gh/dryewo/cyrus-ui-oauth2/branch/master/graph/badge.svg)](https://codecov.io/gh/dryewo/cyrus-ui-oauth2)
[![Clojars Project](https://img.shields.io/clojars/v/cyrus/ui-oauth2.svg)](https://clojars.org/cyrus/ui-oauth2)

Library for UI authentication using OAuth2. Built on top of [Friend](https://github.com/cemerick/friend).

```clj
[cyrus/ui-oauth2 "0.1.1"]
```

## Tutorial

Go to github.com, register an application and copy its client credentials.

Add a namespace in your app, or add to the existing namespace:

```clj
(ns my.app.ui
  (:require [cyrus-ui-oauth2.core :refer [wrap-ui-oauth2]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.session.cookie :as cookie]))


(defn ui-routes [req]
  {:status  200
   :body    "<html><body><h1>Hello</h1></body></html>"
   :headers {"Content-Type" "text/html"}})


(def github {:authorize-url            "https://github.com/login/oauth/authorize"
             :access-token-url         "https://github.com/login/oauth/access_token"
             :client-id                "your-client-id"
             :client-secret            "your-client-secret"
             :scopes                   ["user:email"]
             :allow-anon?              false
             :default-landing-endpoint "/"
             :redirect-endpoint        "/callback"
             :login-endpoint           "/login"
             :logout-endpoint          "/logout"})


(def handler
  (-> ui-routes
      (wrap-ui-oauth2 github)
      (wrap-defaults (-> site-defaults
                         (assoc-in [:session :cookie-attrs :same-site] :lax)
                         (assoc-in [:session :store] (cookie/cookie-store {:key "1qazxsw23edcvfr4"}))))))
```

The example suggests using cookies to store session information.

## Concepts

The library assumes that the application is either fully protected by authentication or fully unprotected.

The library only supports [Authorization Code Grant].

It was build to facilitate creation of admin UIs of microservices that run in public Internet, and need to be protected
by single sign-on (SSO) for employees. Hence the default mode of disallowing unauthenticated access.

[Authorization Code Grant]: https://tools.ietf.org/html/rfc6749#section-4.1

## Tasks

### What if my client credentials are periodically rotated?

If client ID or client secret are expected to change during application runtime, you can
use 0-arity functions in place of their values:

```clj
{:client-id     (fn [] (slurp "client-id.txt"))
 :client-secret (fn [] (slurp "client-secret.txt"))
 ...}
```

### How can I make sure that authentication is expired after some time?

If your identity provider returns expiring tokens and supports [Token Introspection] (github.com does not, however),
you can set `:tokeninfo-url`. This way the library will check the token found in the incoming request against this endpoint,
and will start login process instead of returning content when the token expires.

### Wrong redirect URL is generated because my app is running behind a proxy

Set `:external-url` to the real URL of your application:

```clj
{:external-url "https://example.com"
 ...}
```

[Token Introspection]: https://tools.ietf.org/html/rfc7662#section-2

## Reference

`(wrap-ui-oauth2 handler profile)` takes a [Ring handler] and a map of parameters (profile):

* `:authorize-url` — (required) Authorization endpoint (https://tools.ietf.org/html/rfc6749#section-3.1).
  Usually a full URL starting with `https://`.
* `:access-token-url` — (required) Access token endpoint (https://tools.ietf.org/html/rfc6749#section-3.2)
  Usually a full URL starting with `https://`.
* `:client-id` — (required) Client ID (https://tools.ietf.org/html/rfc6749#section-2.2)
* `:client-secret` — (required) Client Secret (https://tools.ietf.org/html/rfc6749#section-2.3.1)
* `:redirect-endpoint` — (required) Application endpoint for Authorization Response (https://tools.ietf.org/html/rfc6749#section-4.1.2).
  Usually something like `/callback`.
* `:external-url` — (optional) URL part for `:redirect-endpoint`. If not set, will be inferred from `Host` header of the incoming request.
* `:login-endpoint` — (required) Application endpoint to start authentication process. When anonymous access is allowed, 
  Usually something like `/login`.
* `:scopes` — (optional, defaults to empty) List of scopes (https://tools.ietf.org/html/rfc6749#section-3.3)
* `:allow-anon?` — (optional, defaults to `false`) Whether to allow anonymous access. When `false`, redirects unauthenticated
  requests to `:login-endpoint`, which in turn starts authentication process.
* `:tokeninfo-url` — (optional, defaults to empty) Token Introspection endpoint (https://tools.ietf.org/html/rfc7662#section-2).
  Usually a full URL starting with `https://`.
  When set, the Introspection endpoint will be called with every authenticated incoming request in order to check if the token is still valid. 
  Also, an additional key will be added to the incoming request map, containing the Introspection response (https://tools.ietf.org/html/rfc7662#section-2.2):
  ```clj
  :cyrus-ui-oauth2/tokeninfo {...}
  ```
  When not set, this key will contain the original response from `:access-token-url`.  
  If token is expired, a redirect to `:login-endpoint` will be returned instead.
* `:logout-endpoint` — (optional, defaults to empty) Application endpoint to remove authentication information from the browser session.
  When not set, this functionality is unavailable. 
* `:default-landing-endpoint` — (optional, defaults to `/`) Application endpoint for redirecting when original URL is unknown,
  for example, after an external link directly to `:login-endpoint`.

[Ring handler]: https://github.com/ring-clojure/ring/wiki/Concepts#handlers


## License

Copyright © 2018 Dmitrii Balakhonskii

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

----

This documentation is structured according to [4 types of documentation](https://www.divio.com/en/blog/documentation/) principle.
