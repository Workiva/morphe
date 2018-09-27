{:auth
 {:repository-auth
  {#"artifactory"
   {:username #=(eval (System/getenv "ARTIFACTORY_USER")),
    :password #=(eval (System/getenv "ARTIFACTORY_PASS"))}}}}
