{:auth
 {:repository-auth
  {#"workivaeast"
   {:username #=(eval (System/getenv "ARTIFACTORY_PRO_USER")),
    :password #=(eval (System/getenv "ARTIFACTORY_PRO_PASS"))}}}}
