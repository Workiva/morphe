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

(ns morphe.test-helper
  (:require [morphe.core :as m]))

;; aspect from before the convenience bits were added:
(def sideboard (atom nil))
(defn sideboarded
  [fn-form]
  (update fn-form :bodies
          (fn [bodies]
            (for [[body argslist] (map list bodies (:arglists fn-form))]
              (conj body
                    `(reset! sideboard [~@argslist]))))))

(defn sideboarded-new-and-improved
  [fn-form]
  (m/prefix-bodies fn-form `(reset! sideboard '~[(ns-name &ns) &name &meta &env-keys &params])))

(let [okapi 4]
  (m/defn ^{::m/aspects [sideboarded-new-and-improved]}
    body-sideboarded-fn
    [x] (inc x)))
