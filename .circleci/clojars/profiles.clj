{:auth
 {:repository-auth
  {#"https//repo.clojars.org"
   {:username #=(eval (System/getenv "CLOJARS_USER"))
    :password #=(eval (System/getenv "CLOJARS_PASS"))}}}}
