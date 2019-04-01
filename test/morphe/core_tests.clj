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

(ns morphe.core-tests
  (:require [morphe.core :as m]
            [morphe.test-helper :as th]
            [clojure.test :refer :all]))

(m/defn ^{::m/aspects [th/sideboarded]}
  test-function-1
  ([x] (inc x))
  ([x y] (+ x y)))

(deftest test:old-school-aspect
  (testing "can tag with aspects from other namespaces"
    (is (= (test-function-1 5) 6))
    (is (= [5] @th/sideboard))
    (is (= (test-function-1 10 11) 21))
    (is (= [10 11] @th/sideboard))))

(def side-channel (atom nil))
(defn ->side [x] (reset! side-channel x))

(defn prefixed-bodies
  [fn-form]
  (m/prefix-bodies fn-form
                   `(->side '~[(ns-name &ns) &name &meta &env-keys &params])))

(let [zebra 100]
  (m/defn ^{::m/aspects [prefixed-bodies]}
    body-prefixed-fn
    ([x y] (+ x y))
    ([x y z] (+ x y z))))

(deftest test:prefix-bodies
  (is (= 12 (body-prefixed-fn 5 7)))
  (let [[namespace name meta env params] @side-channel]
    (is (= namespace 'morphe.core-tests))
    (is (= name 'body-prefixed-fn))
    (is (= meta '{::m/aspects [prefixed-bodies] :arglists '([x y] [x y z])}))
    (is (= env '#{zebra}))
    (is (= params '[x y])))
  (is (= 18 (body-prefixed-fn 5 6 7)))
  (let [[namespace name meta env params] @side-channel]
    (is (= namespace 'morphe.core-tests))
    (is (= name 'body-prefixed-fn))
    (is (= meta '{::m/aspects [prefixed-bodies] :arglists '([x y] [x y z])}))
    (is (= env '#{zebra}))
    (is (= params '[x y z]))))

(deftest test:prefix-bodies-across-namespace-boundary
  (is (= 11 (th/body-sideboarded-fn 10)))
  (let [[namespace name meta env params] @th/sideboard]
    (is (= namespace 'morphe.test-helper))
    (is (= name 'body-sideboarded-fn))
    (is (= meta '{::m/aspects [sideboarded-new-and-improved] :arglists '([x])}))
    (is (= env '#{okapi}))
    (is (= params '[x]))))

(defn altered-bodies
  [fn-form]
  (m/alter-bodies fn-form
                  `(do (->side '~[(ns-name &ns) &name &meta &env-keys &params &body])
                       ~@&body)))

(let [oryx 55]
  (m/defn ^{::m/aspects [altered-bodies]}
    body-altered-fn
    ([x] (inc x) (comment for good measure) (inc x))
    ([x y] (+ x y))))

(deftest test:alter-bodies
  (is (= 15 (body-altered-fn 14)))
  (let [[namespace name meta env params body] @side-channel]
    (is (= namespace 'morphe.core-tests))
    (is (= name 'body-altered-fn))
    (is (= meta '{::m/aspects [altered-bodies] :arglists '([x] [x y])}))
    (is (= env '#{oryx}))
    (is (= params '[x]))
    (is (= body '((inc x) (comment for good measure) (inc x)))))
  (is (= 20 (body-altered-fn 5 15)))
  (let [[namespace name meta env params body] @side-channel]
    (is (= namespace 'morphe.core-tests))
    (is (= name 'body-altered-fn))
    (is (= meta '{::m/aspects [altered-bodies] :arglists '([x] [x y])}))
    (is (= env '#{oryx}))
    (is (= params '[x y]))
    (is (= body '((+ x y))))))

(defn prefixed-form
  [fn-form]
  (m/prefix-form fn-form
                 `(->side '~[(ns-name &ns) &name &meta &env-keys])))

(deftest test:prefix-form
  (let [hartebeest :kongoni]
    (m/defn ^{::m/aspects [prefixed-form]}
      form-prefixed-fn
      [x] (inc x)))
  (let [[namespace name meta env] @side-channel]
    (is (= 13 (form-prefixed-fn 12)))
    (is (= namespace 'morphe.core-tests))
    (is (= name 'form-prefixed-fn))
    (is (= env '#{hartebeest}))))

(defn altered-form
  [fn-form]
  (m/alter-form fn-form
                `(do (->side '~[(ns-name &ns) &name &meta &env-keys])
                     ~&form)))

(deftest test:alter-form
  (let [springbok :shorty-pants]
    (m/defn ^{::m/aspects [altered-form]}
      form-altered-fn
      [x] (dec x)))
  (let [[namespace name meta env] @side-channel]
    (is (= 11 (form-altered-fn 12)))
    (is (= namespace 'morphe.core-tests))
    (is (= name 'form-altered-fn))
    (is (= env '#{springbok}))))

(defn aspect-1
  [fn-form]
  (m/alter-bodies fn-form `(do (->side :aspect-1) ~@&body)))

(defn aspect-2
  [fn-form]
  (m/prefix-bodies fn-form `(->side :aspect-2)))

(m/defn ^{::m/aspects [aspect-1 aspect-2]}
  aspect-1-wins
  [x] (* x 2))

(m/defn ^{::m/aspects [aspect-2 aspect-1]}
  aspect-2-wins
  [x] (/ x 2))

(deftest test:order-matters
  (is (= 10 (aspect-2-wins 20)))
  (is (= :aspect-2 @side-channel))
  (is (= 4 (aspect-1-wins 2)))
  (is (= :aspect-1 @side-channel)))

(defn higher-order-aspect
  [x]
  (fn [fn-form]
    (m/prefix-bodies fn-form `(->side [~x '~&params]))))

(m/defn ^{::m/aspects [(higher-order-aspect 1)]}
  hired-fn-1
  ([x] (str x))
  ([x y] (vector x y)))

(m/defn ^{::m/aspects [(higher-order-aspect 2)]}
  hired-fn-2
  ([x] (str x))
  ([x y] (vector x y)))

(deftest test:higher-order-aspects
  (is (= ":aspect" (hired-fn-1 :aspect)))
  (is (= [1 '[x]] @side-channel))
  (is (= [10 12] (hired-fn-1 10 12)))
  (is (= [1 '[x y]] @side-channel))
  (is (= ":morphe.core/aspects" (hired-fn-2 ::m/aspects)))
  (is (= [2 '[x]] @side-channel))
  (is (= [:joy :sorrow] (hired-fn-2 :joy :sorrow)))
  (is (= [2 '[x y]] @side-channel)))

(deftest test:sane-errors
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"An error was encountered applying aspect to fn form\."
       (binding [*ns* (find-ns 'morphe.core-tests)]
         (eval '(m/defn ^{::m/aspects [(joysome :always)]}
                  this-will-never-work
                  [x] (inc x)))))))
