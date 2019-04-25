(defproject com.workiva/morphe "1.0.1"
  :description "A Clojure utility for defining and applying aspects for functions."
  :url "https://github.com/Workiva/morphe"
  :license {:name "Eclipse Public License 1.0"}

  :plugins [[lein-cljfmt "0.6.4"]
            [lein-shell "0.5.0"]
            [lein-codox "0.10.3"]]

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.macro "0.1.2"]
                 [org.clojure/tools.logging "0.4.0"]]

  :deploy-repositories {"clojars"
                        {:url "https://repo.clojars.org"
                         :username :env/clojars_username
                         :password :env/clojars_password
                         :sign-releases false}}

  :source-paths      ["src"]
  :test-paths        ["test"]
  
  :aliases {"docs" ["do" "clean-docs," "with-profile" "docs" "codox,"]
            "clean-docs" ["shell" "rm" "-rf" "./documentation"]}

  :codox {:metadata {:doc/format :markdown}
          :themes [:rdash]
          :html {:transforms [[:title]
                              [:substitute [:title "Morphe API Docs"]]
                              [:span.project-version]
                              [:substitute nil]
                              [:pre.deps]
                              [:substitute [:a {:href "https://clojars.org/com.workiva/morphe"}
                                            [:img {:src "https://img.shields.io/clojars/v/com.workiva/morphe.svg"}]]]]}
          :output-path "documentation"}

  :profiles {:dev [{:dependencies [[criterium "0.4.3"]]
                    :source-paths ["dev/src"]}]
             :docs {:dependencies [[codox-theme-rdash "0.1.2"]]}})
