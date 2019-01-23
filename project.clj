(defproject cyrus/ui-oauth2 "0.1.4"
  :description "Library for UI authentication using OAuth2."
  :url "http://github.com/dryewo/cyrus-ui-oauth2"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[com.cemerick/friend "0.2.3" :exclusions [commons-logging
                                                           commons-codec
                                                           slingshot
                                                           ring/ring-core
                                                           org.apache.httpcomponents/httpclient]]
                 [clj-http "3.9.1"]
                 [ring/ring-codec "1.1.1"]
                 [crypto-random "1.2.0"]]
  :plugins [[lein-cloverage "1.0.13"]
            [lein-shell "0.5.0"]
            [lein-ancient "0.6.15"]
            [lein-changelog "0.3.2"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.0"]
                                  [ring/ring-mock "0.3.2"]
                                  [juxt/iota "0.2.3"]
                                  [whitepages/expect-call "0.1.0"]
                                  [ring/ring-defaults "0.3.2"]]}}
  :deploy-repositories [["releases" :clojars]]
  :aliases {"update-readme-version" ["shell" "sed" "-i" "s/\\\\[cyrus\\\\/ui-oauth2 \"[0-9.]*\"\\\\]/[cyrus\\\\/ui-oauth2 \"${:version}\"]/" "README.md"]}
  :release-tasks [["shell" "git" "diff" "--exit-code"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["changelog" "release"]
                  ["update-readme-version"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["deploy"]
                  ["vcs" "push"]])
