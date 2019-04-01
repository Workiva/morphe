(defproject com.workiva/morphe "1.0.0"
  :description "A Clojure utility for defining and applying aspects for functions."
  :url "https://github.com/Workiva/morphe"
  :license {:name "Eclipse Public License 1.0"}
  :plugins [[lein-cljfmt "0.6.4"]
            [lein-shell "0.5.0"]
            [lein-codox "0.10.3"]]
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.macro "0.1.2"]
                 [org.clojure/tools.logging "0.4.0"]]
  
  :source-paths      ["src"]
  :test-paths        ["test"]
  
  :aliases {"docs" ["do" "clean-docs," "with-profile" "docs" "codox,"]
            "clean-docs" ["shell" "rm" "-rf" "./documentation"]}

  :codox {:metadata {:doc/format :markdown}
          :themes [:rdash]
          :output-path "documentation"
          :namespaces [morphe.core]}

  :profiles {:dev [{:dependencies [[criterium "0.4.3"]]
                    :source-paths ["dev/src"]}]
             :docs {:dependencies [[codox-theme-rdash "0.1.2"]]}})
