;; Copyright 2017-2019 Workiva Inc.
;;
;; Licensed under the Eclipse Public License 1.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://opensource.org/licenses/eclipse-1.0.php
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns morphe.core
  (:require [clojure.tools.logging :as log]
            [clojure.tools.macro :refer [symbol-macrolet]])
  (:refer-clojure :exclude [defn]))

(def ^:dynamic *warn-on-noop* false)

(defrecord FnForm [env wrapper namespace metadata fn-name arglists bodies])

(defn- ^{:dynamic true} assert-valid-fdecl ;; From Clojure.core
  "A good fdecl looks like (([a] ...) ([a b] ...)) near the end of defn."
  [fdecl]
  (when (empty? fdecl) (throw (IllegalArgumentException.
                               "Parameter declaration missing")))
  (let [argdecls (map
                  #(if (seq? %)
                     (first %)
                     (throw (IllegalArgumentException.
                             (if (seq? (first fdecl))
                               (format "Invalid signature \"%s\" should be a list" %)
                               (format "Parameter declartion \"%s\" should be a vector" %)))))
                  fdecl)
        bad-args (seq (remove #(vector? %) argdecls))]
    (when bad-args
      (throw (IllegalArgumentException. (str "Parameter declaration \"" (first bad-args)
                                             "\" should be a vector"))))))

(defn- sigs ;; From Clojure.core
  [fdecl]
  (assert-valid-fdecl fdecl)
  (let [asig (fn [fdecl]
               (let [arglist (first fdecl) ;elide implicit macro args
                     arglist (if (clojure.lang.Util/equals '&form (first arglist))
                               (clojure.lang.RT/subvec arglist 2 (clojure.lang.RT/count arglist))
                               arglist)
                     body (next fdecl)]
                 (if-not (map? (first body))
                   arglist
                   (if-not (next body)
                     arglist
                     (with-meta arglist (conj (if (meta arglist) (meta arglist) {}) (first body)))))))
        resolve-tag (fn [argvec]
                      (let [m (meta argvec)
                            ^clojure.lang.Symbol tag (:tag m)]
                        (if-not (instance? clojure.lang.Symbol tag)
                          argvec
                          (if-not (clojure.lang.Util/equiv (.indexOf (.getName tag) ".") -1)
                            argvec
                            (if-not (clojure.lang.Util/equals nil
                                                              (clojure.lang.Compiler$HostExpr/maybeSpecialTag tag))
                              argvec
                              (let [c (clojure.lang.Compiler$HostExpr/maybeClass tag false)]
                                (if-not c
                                  argvec
                                  (with-meta argvec (assoc m :tag (clojure.lang.Symbol/intern (.getName c)))))))))))]
    (if (seq? (first fdecl))
      (loop [ret [] fdecls fdecl]
        (if fdecls
          (recur (conj ret (resolve-tag (asig (first fdecls)))) (next fdecls))
          (seq ret)))
      (list (resolve-tag (asig fdecl))))))

;; The following is taken straight from clojure.core/defn (sense a
;; theme?), with modifications to output a FnForm record instead of
;; defn form.

(clojure.core/defn parse-defn
  "This duplicates Clojure.core's `defn`, but instead of constructing the function
  definition, this returns a FnForm record containing the result of parsing the
  defn."
  [&form &env name & fdecl]
  (when-not (symbol? name)
    (throw (IllegalArgumentException. "The first argument to a def form must be a symbol.")))
  (let [m (if (string? (first fdecl))
            {:doc (first fdecl)}
            {})
        fdecl (if (string? (first fdecl))
                (next fdecl)
                fdecl)
        m (if (map? (first fdecl))
            (conj m (first fdecl))
            m)
        fdecl (if (map? (first fdecl))
                (next fdecl)
                fdecl)
        fdecl (if (vector? (first fdecl))
                (list fdecl)
                fdecl)
        m (if (map? (last fdecl))
            (conj m (last fdecl))
            m)
        fdecl (if (map? (last fdecl))
                (butlast fdecl)
                fdecl)
        m (conj {:arglists (list 'quote (sigs fdecl))} m)
        m (let [inline (:inline m)
                ifn (first inline)
                iname (second inline)]
            (if (and (= 'fn ifn) (not (symbol? iname)))
              (->> (next inline)
                   (cons (clojure.lang.Symbol/intern (.concat (.getName ^clojure.lang.Symbol name) "__inliner")))
                   (cons ifn)
                   (assoc m :inline))
              m))
        m (conj (if (meta name) (meta name) {}) m)
        params (map first fdecl)
        bodies (map rest fdecl)]
    (map->FnForm {:wrapper `(do ::form)
                  :env &env
                  :namespace *ns*
                  :metadata m
                  :fn-name name
                  :arglists params
                  :bodies bodies})))

(clojure.core/defn fn-form->defn
  "Finally turns the FnForm record back into a complete code body."
  [fn-form]
  ;; vvv some more bits forked out of clojure.core/defn.
  (let [definition (list `def (with-meta (:fn-name fn-form) (:metadata fn-form))
                         (with-meta (cons `fn (map cons (:arglists fn-form) (:bodies fn-form)))
                           {:rettag (:tag (:metadata fn-form))}))]
    (if (not= (:wrapper fn-form) `(do ::form))
      ;; vvv replaces the ::definition with the defn form, inside the wrapper form. Wrapped in do.
      (clojure.walk/postwalk-replace {::form definition}
                                     (:wrapper fn-form))
      definition)))

(clojure.core/defn apply-aspect
  [fn-form aspect]
  (try
    (let [aspect (cond (symbol? aspect)
                       (ns-resolve (:namespace fn-form) aspect)
                       (list? aspect)
                       (eval aspect))]
      (aspect fn-form))
    (catch Throwable e
      (throw
       (ex-info "An error was encountered applying aspect to fn form."
                {:fn-form fn-form
                 :aspect aspect}
                e)))))

(defmacro defn
  "Should behave exactly like clojure.core/defn, except:
  You can tag the fn name with aspects:
  `^{:morphe.core/aspects [aspects ...]}`
  The aspects must be functions of one argument that know how to manipulate a
  morphe.core/FnForm record.

  In implementation, it basically uses the guts of clojure.core/defn to parse
  the definition, representing the parsed form with a FnForm record,
  which then gets operated on by composable modification fns (aspects).

  The FnForm record has the following fields:
    :env - the `&env` var inside the `defn` call.
    :wrapper - A single expression not equal to, but representing any code that
               should wrap the `defn` call.
    :namespace - The namespace in which the fn is being interned.
    :fn-name - The symbolic name of the function being defined.
    :metadata - The metadata that was attached to the fn-name.
    :arglists - A sequence of arglists, one for each arity.
    :bodies - A sequence of arity bodies, where each body is a collection of expressions."
  {:arglists '([name doc-string? attr-map? [params*] prepost-map? body]
               [name doc-string? attr-map? ([params*] prepost-map? body) + attr-map?])}
  [fn-name & fdecl]
  (let [aspects (get (meta fn-name) ::aspects)
        fn-form (apply parse-defn &form &env fn-name fdecl)]
    (when (and (empty? aspects) *warn-on-noop*)
      (log/warn (format "%s/%s defined with morphe.core/defn, but no aspects were found!"
                        (:namespace fn-form)
                        (:fn-name fn-form))))
    (fn-form->defn (reduce apply-aspect fn-form aspects))))

(defn- ->anaphoric-binding
  ([fn-form anaphore]
   (assert (not (or (= anaphore '&body)
                    (= anaphore '&params))))
   (->anaphoric-binding fn-form nil nil anaphore))
  ([fn-form params anaphore]
   (assert (not (= anaphore '&body)))
   (->anaphoric-binding fn-form params nil anaphore))
  ([fn-form params body anaphore]
   [anaphore
    (condp = anaphore
      '&body body
      '&params params
      '&ns `(:namespace ~fn-form)
      '&name `(:fn-name ~fn-form)
      '&meta `(:metadata ~fn-form)
      '&form `(:wrapper ~fn-form)
      '&env-keys `(set (keys (:env ~fn-form))))]))

(defn- anaphoric-scope
  ([sym:fn-form anaphores expression]
   `(symbol-macrolet ~(into []
                            (mapcat (partial ->anaphoric-binding sym:fn-form))
                            anaphores)
                     ~expression))
  ([sym:fn-form sym:params anaphores expression]
   `(symbol-macrolet ~(into []
                            (mapcat (partial ->anaphoric-binding sym:fn-form sym:params))
                            anaphores)
                     ~expression))
  ([sym:fn-form sym:params sym:body anaphores expression]
   `(symbol-macrolet ~(into []
                            (mapcat (partial ->anaphoric-binding sym:fn-form sym:params sym:body))
                            anaphores)
                     ~expression)))

(defmacro alter-form
  "Allows specification of code that would wrap the entire `defn` form.
  Useful mainly for providing a lexical scope (e.g., evaluating the `defn`
  within the body of a `let`). Provides:
    * &ns - The namespace in which this fn is being interned
    * &name - The symbol used to name this defn.
    * &meta - The metadata attached to the fn name.
    * &env-keys - The keys of the &env map known to the `defn` macro.
    * &form - A placeholder for the actual form -- not the form itself.
  NOTA BENE: &form should always be assumed to represent a *single* expression.
  Example: (alter-form fn-form `(binding [*my-var* 3 ~&form)))"
  {:style/indent 1}
  [fn-form expression]
  (let [sym:fn-form (gensym 'fn-form)
        expression (->> expression
                        (anaphoric-scope sym:fn-form '#{&ns &name &env-keys &meta &form}))]
    `(let [~sym:fn-form ~fn-form]
       (assoc ~sym:fn-form :wrapper ~expression))))

(defmacro prefix-form
  "Allows the specification of an expression that will be evaluated before
  the `defn` form (presumably for side-effects). Provides:
    * &ns - The namespace in which this fn is being interned
    * &name - The symbol used to name this defn.
    * &meta - The metadata attached to the fn name.
    * &env-keys - The keys of the &env map known to the `defn` macro.
  Example:
  (prefix-form fn-form
               `(println (format \"Compiling %s/%s now.\"
                                 (ns-name &ns)
                                 &name)))"
  {:style/indent 1}
  [fn-form expression]
  (let [sym:fn-form (gensym 'fn-form)
        expression (->> expression
                        (anaphoric-scope sym:fn-form '#{&ns &name &env-keys &meta}))]
    `(let [~sym:fn-form ~fn-form]
       (alter-form ~sym:fn-form
                   `(do ~~expression
                        ~~'&form)))))

(defn alter-bodies*
  "Takes a fn-form and a function of args [params body] and replaces each body
  in the fn-form with the result of applying the function to the params and
  the body! body should be assumed to be a collection of valid expressions."
  {:style/indent 1}
  [fn-form f]
  (update fn-form
          :bodies
          (fn [bodies]
            (map f (:arglists fn-form) bodies))))

(defmacro alter-bodies
  "Allows specification of code that should wrap each body of the `defn`
  form. Provides:
    * &params - The paramaters corresponding to this arity.
    * &body - The collection of expressions in the body of this arity.
    * &ns - The namespace in which this fn is being interned
    * &name - The symbol used to name this defn.
    * &meta - The metadata attached to the fn name.
    * &env-keys - The keys of the &env map known to the `defn` macro.
  NOTA BENE: &body is an *ordered collection* of valid expressions.
  Example:
  (alter-bodies fn-form
                `(binding [*scope* ~[(ns-name &ns) &name &params]]
                   ~@&body))"
  {:style/indent 1}
  [fn-form expression]
  (let [sym:params (gensym 'params)
        sym:body (gensym 'body)
        sym:fn-form (gensym 'fn-form)
        anaphores '#{&params &body &ns &name &meta &env-keys}
        expression-fn `(fn ~[sym:params sym:body]
                         (list ~(->> expression
                                     (anaphoric-scope sym:fn-form sym:params sym:body anaphores))))]
    `(let [~sym:fn-form ~fn-form]
       (alter-bodies* ~sym:fn-form ~expression-fn))))

(defn prefix-bodies*
  "Takes a fn-form and a function of args [params] and prefixes each body
  in the fn-form with the result of applying the function to the params!"
  {:style/indent 1}
  [fn-form f]
  (update fn-form
          :bodies
          (fn [bodies]
            (map cons
                 (map f (:arglists fn-form))
                 bodies))))

(defmacro prefix-bodies
  "Allows the specification of an expression that will be added to the beginning
  of each fn arity (presumably for side-effects). Provides:
    * &params - The paramaters corresponding to this arity.
    * &ns - The namespace in which this fn is being interned
    * &name - The symbol used to name this defn.
    * &meta - The metadata attached to the fn name.
    * &env-keys - The keys of the &env map known to the `defn` macro.
  Example: (prefix-bodies fn-form `(assert (even? 4) \"Math still works.\"))"
  {:style/indent 1}
  [fn-form expression]
  (let [sym:params (gensym 'params)
        sym:fn-form (gensym 'fn-form)
        anaphores '#{&params &ns &name &meta &env-keys}
        expression-fn `(fn ~[sym:params]
                         ~(->> expression
                               (anaphoric-scope sym:fn-form sym:params anaphores)))]
    `(let [~sym:fn-form ~fn-form]
       (prefix-bodies* ~sym:fn-form ~expression-fn))))
