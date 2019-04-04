# Morphe (μορφή) [![Clojars Project](https://img.shields.io/clojars/v/com.workiva/morphe.svg)](https://clojars.org/com.workiva/morphe) [![CircleCI](https://circleci.com/gh/Workiva/morphe/tree/master.svg?style=svg)](https://circleci.com/gh/Workiva/morphe/tree/master)

> "Thus if we regard objects independently of their attributes and investigate any aspect of them as so regarded, we shall not be guilty of any error on this account, any more than when we draw a diagram on the ground and say that a line is a foot long when it is not; because the error is not in the premises. The best way to conduct an investigation in every case is to take that which does not exist in separation and consider it separately; which is just what the arithmetician or the geometrician does."
> 
> Aristotle, *Metaphysics*

---

<!-- toc -->

- [What Is It?](#what-is-it)
- [How does it work?](#how-does-it-work)
- [API Documentation](#api-documentation)
- [Details](#details)
- [`morphe.core` utilities](#morphecore-utilities)
    + [`defn`](#defn)
    + [`parse-defn: [&form &env name & fdecl]`](#parse-defn-form-env-name--fdecl)
    + [`fn-form->defn: [fn-form]`](#fn-form-defn-fn-form)
    + [`prefix-form: [fn-form expression]`](#prefix-form-fn-form-expression)
    + [`alter-form: [fn-form expression]`](#alter-form-fn-form-expression)
    + [`prefix-bodies: [fn-form expression]`](#prefix-bodies-fn-form-expression)
    + [`alter-bodies: [fn-form expression]`](#alter-bodies-fn-form-expression)
    + [`*warn-on-noop*`](#warn-on-noop)
- [Examples](#examples)
  * [Logging/tracing call sites](#loggingtracing-call-sites)
  * [Tagging for metrics](#tagging-for-metrics)
  * [Mix & match](#mix--match)
  * [Macrotic Transformations](#macrotic-transformations)
- [Maintainers and Contributors](#maintainers-and-contributors)
  * [Active Maintainers](#active-maintainers)
  * [Previous Contributors](#previous-contributors)

<!-- tocstop -->

## What Is It?

Gather round, and I shall tell you a fine tale. Once upon a time, there was a simple function in an API, a thin wrapper over more meaty code:

```clojure
(defn do-a-thing [x stuff] (.doThatThing x stuff))
```

But a time came when we wanted to log every time it was called:

```clojure
(defn do-a-thing
  [x stuff]
  (log/trace "calling function: app.api/do-a-thing")
  (.doThatThing x stuff))
```

Of course, we wanted to do the same with many functions in our codebase. This would lead to unnecessary code bloat, so we employed a standard and idiomatic solution that simultaneously:

 - reduced the amount of bureucratic code.
 - ensured that we could switch out clojure.tools/logging for another solution in all places at any time.
 - avoided bloating the call stack with unnecessary functional wrapping.

That is, we defined a new `defn`-like macro that would automatically generate the appropriate logging line. This was an improvement. We could replace the definition for `do-a-thing` and the many other logged functions with this simple line:

```clojure
(def-logged-fn do-a-thing [x stuff] (.doThatThing x stuff))
```

Soon after, we wanted to know how long each call to `do-a-thing` would take:

```clojure
(def do-a-thing-timer (metrics/timer "Timer for the function: app.api/do-a-thing"))

(metrics/register metrics/DEFAULT
                  ["app.api" "do-a-thing" "timer"]
                  do-a-thing-timer)

(def-logged-fn do-a-thing
  [x stuff]
  (let [context (.time timer)
        result (.doThatThing x stuff)]
    (.stop context)
    result)))
```


At first, all the functions we were logging were also functions we wanted to time, so we wrote a macro to generate all this code and let us go back to something simple, this time saving ourselves a few hundred lines of fragile copy-paste boilerplate:

```clojure
(def-logged-and-timed-fn do-a-thing [x stuff] (.doThatThing x stuff))
```

But alas! Our needs still grew, and several things happened at once. We incorporated tracing into our codebase, and we no longer wished for all our logged functions to be timed, or for all our timed functions to be traced, or all our traced functions to be logged -- we wanted any combination of the three. The optimizations we'd made no longer applied, so our little one-line wrapper was up to twenty-seven lines. Even after applying other mitigation techniques, it was not ideal:

```clojure
(let [timer (metrics/timer "Timer for the function: api.api/do-a-thing")]
  (metrics/register metrics/DEFAULT
                    ["api.api" "do-a-thing" "timer"]
                    timer)
  (defn do-a-thing
    [x stuff]
    (log/trace "calling function: app.api/do-a-thing")
    (metrics/with-timer timer
      (tracing/with-tracing "app.api/do-a-thing:[x stuff]"
        (.doThatThing x stuff))))))
```

Multiply this effect by the number of functions we apply telemetry to across our entire service. It is tedious, and contains a good deal of copy-paste code, fragile under inevitable future changes (for instance, swapping out a logging library or modifying the metrics implementation). Moreover, all that boilerplate is very distacting if you care about the business logic and nothing else. What it seemed we really *needed* was a full suite of `def-*-fn`s:

 - `def-logged-fn`
 - `def-traced-fn`
 - `def-timed-fn`
 - `def-logged-traced-fn`
 - `def-logged-timed-fn`
 - `def-traced-timed-fn`
 - `def-logged-traced-timed-fn`

That, of course, is ridiculous. Besides, with just one additional fourth axis, we'd need 15 of these. For n, 2<sup>n</sup>-1.

The key to solving our problem once and for all was to recognize that these were all _completely independent_ [aspects](https://en.wikipedia.org/wiki/Aspect-oriented_programming) of a function definition. None of the manual transformations depended on any of the others. Thus was born `morphe`. Our one-liner could once again be a one-liner:

```clojure
(m/defn ^{::m/aspects [timed logged traced]} do-a-thing [x stuff] (.doThatThing x stuff))
```

And in case you are skeptical as to how this solves any problem in the first place, remember that the best predictor of bug count in a code base is the *size* of the code base. This library has a number of potential applications, but the easiest all involve removing boilerplate.

## How does it work?

In Clojure's grammar, `defn` forms have many optional and variadic terms. In order to compile a function, Clojure's [`defn`](https://github.com/clojure/clojure/blob/clojure-1.9.0-alpha14/src/clj/clojure/core.clj#L283) first parses the definition you have written, then it writes an actual function form which is compiled.

In this library, we have forked `defn`, splitting it into its two fundamental components: the parser and writer. The parser outputs a `FnForm` record which is consumed by the writer. But between being parsed and being compiled, the FnForm record can easily be processed and modified by *aspect-defining* functions.

In the line above, `^{::m/aspects [...]}` tells Clojure's [reader](https://clojure.org/reference/reader) to attach a map of metadata to a symbol. `morphe.core/defn` parses the function definition as normal, then examines the symbol's metadata to determine which aspects it is tagged with. The library then calls the tagged aspect functions to modify the parsed form. Once all such tags have been applied, Morphe's `defn` passes the parsed form along to the writer, just as `clojure.core/defn` implicitly would have done.

It is fairly straightforward to modify the FnForm record. But `morphe` provides a number of conveniences to make writing common aspect transformations even simpler; for example, wrapping the whole definition (perhaps in the body of a `let`), or prefixing every body of the function (perhaps with generated log statements). For instance, defining a simple trace-level logging transformation is easy:

```clojure
(defn traced
  "Inserts a log/trace call as the first item in the fn body."
  [fn-form]
  (m/prefix-bodies fn-form
                   `(log/trace "calling function: "
                               ~(format "%s/%s:%s" (ns-name &ns) &name &params)))))
```
This is equivalent to the following method, which does not use any convenience functions and instead modifies the `FnForm` record directly:

```clojure
(defn traced
  "Inserts a log/trace call as the first item in the fn body."
  [fn-form]
  (let [namespaced-fn-name (format "%s/$s"
                                   (str (ns-name (:namespace fn-form)))
                                   (str (:fn-name fn-form)))]
    (assoc fn-form :bodies
           (for [[body args] (apply map list ((juxt :bodies :arglists) fn-form))]
             (conj body `(log/trace ~(format "calling function: %s:%s"
                                             namespaced-fn-name
                                             args)))))))
```

## API Documentation

[API documentation can be found here.](/documentation/index.html)

## Details

If your use case is complex, you can modify the FnForm record directly. If you have never written [Clojure macros](https://clojure.org/reference/macros), there are a few tricky things to this process. The community is helpful, and help is also available in [book](https://www.braveclojure.com/writing-macros/) [form](https://www.amazon.com/Lisp-Advanced-Techniques-Common/dp/0130305529).

## `morphe.core` utilities

Clojure's `defmacro` is an [anaphoric macro](https://en.wikipedia.org/wiki/Anaphoric_macro). Code inside `defmacro` has access to two special variables, `&env` and `&form`. `&env` is "a map of local bindings at the point of macro expansion. The env map is from symbols to objects holding compiler information about that binding." `&form` is "the actual form (as data) that is being invoked."

Many of morphe's utilities follow this theme. Depending on the utility, some of the following variables are available:

- `&ns`: the namespace in which the aspect-modified function is being run.
- `&name`: the unqualified name given to the function.
- `&env-keys`: the *keyset* of the `&env` map as seen by the `morphe.core/defn` macro itself (i.e., set of symbols bound in a local scope)
- `&meta`: the metadata with which the function has been tagged
- `&params`: the paramaters vector for *a particular* arity of the function.
- `&body`: the collection of expression(s) constituting *a particular* arity of the function.
- `&form`: an *uninspectable* representation of the collection of expressions for the entire function declaration; useful to wrap the whole `defn` with a lexical scope.

#### `defn`

A drop-in replacement for Clojure's `defn`. In the simple case, the two should be indistinguishable. But you can tag the fn-name with metadata, under the keyword `:morphe.core/aspects`, to trigger the application of aspects. `morphe.core/defn` first calls `parse-defn`, then applies the tagged aspects in order, then calls `fn-form=>defn`.

#### `parse-defn: [&form &env name & fdecl]`

You probably don't want to use this, but it's available if you do. This function contains half of the implementation of Clojure.core's `defn`. Intended to be used with `fn-form->defn`. It takes the same arguments as `defn`, but this returns a FnForm record containing the result of parsing the defn. `&form` and `&env` must be passed in explicitly: they are implicit arguments [available only inside macro definitions](https://clojure.org/reference/macros).

#### `fn-form->defn: [fn-form]`

As with `parse-defn`, this is another low-level function you probably don't need. This function contains the second half of the implementation of Clojure.core's `defn`. Intended to be used with `parse-defn`. It takes a FnForm record as output by `parse-defn`, and returns a `(def ...)` form in the same manner as Clojure's `defn` macro.

#### `prefix-form: [fn-form expression]`

Anaphoric macro, providing `&ns`, `&name`, `&env-keys`, and `&meta`.

This will prefix the entire form with the provided expression. Example: 

```
(prefix-form
  fn-form
  `(def gets-defined-first 3))
```

#### `alter-form: [fn-form expression]`

Anaphoric macro, providing `&ns`, `&name`, `&env-keys`, `&meta`, and `&form`.

This will wrap the entire form, with the form's location in the code specified by `&form`. `&form` must be assumed to be a *single valid expression*, not a sequence of expressions.

Example:

```clojure
(alter-form fn-form
           `(binding [*my-var* 3] &form))
```

#### `prefix-bodies: [fn-form expression]`

Anaphoric macro, providing `&ns`, `&name`, `&env-keys`, `&meta`, and `&params`.

This will prefix each body of the function with the provided expression. `&params` will evaluate to the parameter list corresponding to each body.

Example:

```clojure
(prefix-bodies fn-form
               `(assert (even? 4)
                        (format "Math still works in the %s arity."
                                &params)))
```

#### `alter-bodies: [fn-form expression]`

Anaphoric macro, providing `&ns`, `&name`, `&env-keys`, `&meta`, `&params`, and `&body`.

For each arity of the function, this *replaces* the clauses with the given expression; `&params` and `&body` are bound appropriately for each arity, and `&body` is assumed to be a *sequence of valid expressions*, not a single valid expression. Typically used for wrapping each body somehow.

Example:

```
(alter-bodies fn-form
             `(binding [*some-scope* ~{:ns &ns,
                                       :sym &name,
                                       :arity &params}]
                ~@&body))
```

#### `*warn-on-noop*`

False by default. When this is true, morphe will attempt to generate compile-time warnings whenever `morphe.core/defn` is unable to find any aspects to apply (perhaps due to a typo).

## Examples

### Logging/tracing call sites

Let's say you want to log every time a method is called, along with the arity. Usually you want this to be at the warn level, but sometimes you want debug or info.

```clojure
(defn logged
  "Higher order fn, returning an aspect fn. Inserts a log call as the
   first item in each fn body."
  ([] (logged :warn))
  ([level]
   (fn [fn-form]
     (d/prefix-bodies
       fn-form
       `(log/log ~level
                 ~(format "Logging at %s level: Entering fn %s/%s:%s."
                          level
                          &ns
                          &name
                          &params))))))

;; Now let's use it:
(m/defn ^{::m/aspects [(logged :debug)]}
        my-logged-fn
  ([x] x)
  ([x y] (+ x y))
  ([x y z] (+ x y z))
  ([x y z & more] (apply + x y z more)))
  
;; This expands to:
(defn my-logged-fn
  ([x]
    (log/log :debug "Logging at :debug level: Entering fn my-ns/my-logged-fn:[x].")
    x)
  ([x y]
    (log/log :debug "Logging at :debug level: Entering fn my-ns/my-logged-fn:[x y].")
    (+ x y))
  ([x y z]
    (log/log :debug "Logging at :debug level: Entering fn my-ns/my-logged-fn:[x y z].")
    (+ x y z))
  ([x y z & more]
    (log/log :debug "Logging at :debug level: Entering fn my-ns/my-logged-fn:[x y z & more].")
    (apply + x y z more)))
```

### Tagging for metrics

Now suppose you want to time a function.

```clojure
(defn timed
  "Creates a lexical scope for the defn with a codehale timer defined, which is
  then used to time each function call."
  [fn-form]
  (let [timer (gensym 'timer)]
    (-> fn-form
        (d/alter-form `(let [~timer (metrics/timer ~(format "Timer for the function: %s"
                                                           (symbol (str &ns) &name)))]
                        (metrics/register metrics/DEFAULT ~[(str &ns) (str &name) "timer"])
                        ~@&form))
        (d/alter-bodies `(metrics/with-timer ~timer ~@&body)))))

;; Let's use it:
(m/defn ^{::m/aspects [timed]}
        my-timed-fn
  ([x] x)
  ([x y] (+ x y))
  ([x y z] (+ x y z))
  ([x y z & more] (apply + x y z more)))
  
;; This expands to:
(let [timer7068 (metrics/timer "Timer for the function: my-ns/my-timed-fn")]
  (metrics/register metrics/DEFAULT ["my-ns" "my-timed-fn" "timer"])
  (defn my-timed-fn
    ([x]
      (metrics/with-timer timer7068
        x))
    ([x y]
      (metrics/with-timer timer7068
        (+ x y)))
    ([x y z]
      (metrics/with-timer timer7068
        (+ x y z)))
    ([x y z & more]
      (metrics/with-timer timer7068
        (apply + x y z more))))
```

### Mix & match

Let's do both.

```clojure
(m/defn ^{::m/aspects [timed (logged :debug)]}
        my-amazing-fn
  ([x] x)
  ([x y] (+ x y))
  ([x y z] (+ x y z))
  ([x y z & more] (apply + x y z more)))
  
;; This expands to:
(let [timer7068 (metrics/timer "Timer for the function: my-ns/my-amazing-fn")]
  (metrics/register metrics/DEFAULT ["my-ns" "my-amazing-fn" "timer"])
  (defn my-amazing-fn
    ([x]
      (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x].")
      (metrics/with-timer timer7068
        x))
    ([x y]
      (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x y].")
      (metrics/with-timer timer7068
        (+ x y)))
    ([x y z]
      (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x y z].")
      (metrics/with-timer timer7068
        (+ x y z)))
    ([x y z & more]
      (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x y z & more].")
      (metrics/with-timer timer7068
        (apply + x y z more))))
```

Change the aspects' order in the tagged vector, change the order of application:

```clojure
(m/defn ^{::m/aspects [(logged :debug) timed]}
        my-amazing-fn
  ([x] x)
  ([x y] (+ x y))
  ([x y z] (+ x y z))
  ([x y z & more] (apply + x y z more)))
  
;; This expands to:
(let [timer7068 (metrics/timer "Timer for the function: my-ns/my-amazing-fn")]
  (metrics/register metrics/DEFAULT ["my-ns" "my-amazing-fn" "timer"])
  (defn my-amazing-fn
    ([x]
      (metrics/with-timer timer7068
        (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x].")
        x))
    ([x y]
      (metrics/with-timer timer7068
        (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x y].")
        (+ x y)))
    ([x y z]
      (metrics/with-timer timer7068
        (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x y z].")
        (+ x y z)))
    ([x y z & more]
      (metrics/with-timer timer7068
        (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x y z & more].")
        (apply + x y z more))))
```

### Macrotic Transformations

In the examples so far, similar effects could be achieved via (possibly clunky) functional composition. Perhaps embedding the `&params` vector exactly as written in source code would be difficult, though one might get around this by exploiting metadata on vars. But the fact that we are operating on the function's *code* rather than the function itself does allow interesting transformations one could not effect purely functionally. Consider this funny little example I have used in practice (notice how some of the code gets restructured in the second arity):

```clojure
(m/defn ^{::m/aspects [(synchronize-on state #{pojo-1 pojo-2})]}
        safely-update-then-calculate
  ([state pojo-1]
    (when-let [x (.inspect pojo-1)]
      (.update pojo-1 (:one @state))
      (expensive-calculation x))
  ([state pojo-1 pojo-2]
    (when-let [x (.inspect pojo-1)]
      (.update pojo-1 (:one @state))
      (.update pojo-2 (:two @state))
      (expensive-calculation x))))

;; expands to:
(defn safely-update-then-calculate
  ([state pojo-1]
    (when-let [x (locking state
                   (when-let [x46735 (.inspect pojo-1)]
                     (.update pojo-1 (:one @state))
                     x46735))]
      (expensive-calculation x)))
  ([state pojo-1 pojo-2]
    (when-let [x (locking state
                   (when-let [x46736 (.inspect pojo-1)]
                     (.update pojo-1 (:one @state))
                     (.update pojo-2 (:two @state))
                     x46736))]
       (expensive-calculation x))))
```

## Maintainers and Contributors

### Active Maintainers

-

### Previous Contributors

- Timothy Dean <galdre@gmail.com>
