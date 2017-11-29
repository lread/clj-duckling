(ns clj-duckling.core
  "The main module with public API."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as string]
   [integrant.core :as ig]
   [duct.logger :refer [log]] 
   [plumbing.core :as p]
   [environ.core :refer [env]]
   [clojure.test :refer :all]
   [clj-duckling.system :as sys]
   [clj-duckling.corpus.core :as corpus]
   clj-duckling.spec
   [clj-duckling.util.engine :as engine]
   [clj-duckling.util.learn :as learn]
   ;; [clj-duckling.resource :as res]
   [clj-duckling.dims.api :as dims]
   [clj-duckling.util.time :as time]
   [clj-duckling.util.core :as util]))

(defonce rules-map (atom {}))
(defonce corpus-map (atom {}))
(defonce classifiers-map (atom {}))

;; for tests
(use-fixtures :once
  (fn [tests]
    (println "============== start core tests")
    (alter-var-root #'sys/system (fn [_] (ig/init (-> "test.edn"
                                                      sys/read-config
                                                      sys/prep))))
    (tests)
    (ig/halt! sys/system)
    (println "============== stop core tests")))

(defn default-context
  "Build a default context for testing. opt can be either :corpus or :now"
  [opt]
  {:reference-time (case opt
                     :corpus (time/t -2 2013 2 12 4 30)
                     :now (time/now))})

(defn- get-classifier
  "Get module's classifiers.

  Args:
  id (string): module name

  Returns:
  (list): a classifiers list."

  [id]
  (when id
    (get @classifiers-map (keyword id))))

(deftest get-classifier-test
  (is (= ["<number> product" {:classes {true {:n 1, :unk-proba -1.0986122886681098, :class-proba 0.0, :feat-probas {"integer (0..19)carne" 0.0}}}}]
         (first (get-classifier "ro$core")))))

(defn- get-rules
  "Get module's rules.

  Args:
  id (string): module name

  Returns:
  (list): a rules map list."
  [id]
  (when id
    (get @rules-map (keyword id))))

(deftest get-rules-test
  (is (nil? (s/explain-data :rule/rules (get-rules "ro$core")))))

(defn- compare-tokens
  "Compares two candidate tokens a and b for runtime selection.
  wanted-dim is a hash whose keys are the :dim wanted by the caller, the value
  can be anything truthy.
  Returns nil: not comparable 0: equal 1: greater -1: lesser"
  [a b classifiers wanted-dims]
  {:pre [(map? classifiers)]}
  (let [same-dim (= (:dim a) (:dim b))
        wanted-a (get wanted-dims (:dim a))
        wanted-b (get wanted-dims (:dim b))
        cmp-interval (util/compare-intervals
                      [(:pos a) (:end a)]
                      [(:pos b) (:end b)])] ; +1 0 -1 nil
    ;;(printf "Comparing %d and %d \n" (:index a) (:index b))
    (if-not same-dim
      ;; unless a is wanted and covers b, or the contrary, they are not comparable
      (cond (and wanted-a (= 1 cmp-interval)) 1
            (and wanted-b (= -1 cmp-interval)) -1
            :else nil)
      (if (not= 0 cmp-interval)
        cmp-interval ; one interval recovers the other
        (compare (:log-prob a) (:log-prob b))))))

(defn- select-winners*
  [compare-fn resolve-fn already-selected candidates]
  (if (seq candidates)
    (let [[maxima others] (util/split-by-partial-max
                           compare-fn
                           candidates
                           (concat already-selected candidates))
          new-winners (->> maxima
                           (mapcat resolve-fn)
                           (filter :value))] ; remove unresolved
      (if (seq maxima)
        (recur compare-fn resolve-fn (concat already-selected new-winners) others)
        already-selected))
    already-selected))

(defn- select-winners
  "Winner= token that is not 'smaller' (in the sense of the provided partial
  order) than another winner, and that resolves to a value"
  [compare-fn log-prob-fn resolve-fn candidates]
  (->> candidates
       (map #(assoc % :log-prob (log-prob-fn %)))
       (select-winners* compare-fn resolve-fn [])
       (map #(dissoc % :log-prob))))

(defn analyze
  "Parse a sentence, returns the stash and a curated list of winners.

  Args:
  s (string):
  context (map):
  module (keyword): language module
  targets (coll): a coll of {:dim dim :label label} : only winners of these dims are
                  kept, and they receive a :label key = the label provided.
                  If no targets specified, all winners are returned.
  base-stash ():

  Returns:
  (map): a map with 2 keys :stash and :winners
  "
  [s context module targets base-stash]
  {:pre [s context module]}
  (let [classifiers (get-classifier module)
        logger (get context :logger (util/get-default-logger))
        _ (when-not (map? classifiers)
            (log logger :error ::module-not-loaded {:module module}))
        rules (get-rules module)
        stash (engine/pass-all s rules base-stash)
        ;; add an index to tokens in the stash
        stash (map #(if (map? %1) (assoc %1 :index %2) %1)
                   stash
                   (iterate inc 0))
        dim-label (when (seq targets) (into {} (for [{:keys [dim label]} targets]
                                                 [(keyword dim) label])))
        winners (->> stash
                     (filter :pos)
                     ;; just keep the dims we want, and add the label key
                     (p/?>> dim-label (keep #(when-let [label (get dim-label (:dim %))]
                                               (assoc % :label label))))

                     (select-winners
                      #(compare-tokens %1 %2 classifiers dim-label)
                      #(learn/route-prob % classifiers)
                      #(engine/resolve-token % context module))

                     ;; adapt the keys for the outside world
                     (map (fn [{:keys [pos end text] :as token}]
                            (merge token {:start pos
                                          :end end
                                          :body text}))))]
    ;; (log/debugf "stash: %s" (with-out-str (clojure.pprint/pprint stash)))
    ;; (log/debugf "winners: %s" (with-out-str (clojure.pprint/pprint winners)))
    {:stash stash :winners winners}))

;;--------------------------------------------------------------------------
;; REPL utilities
;;--------------------------------------------------------------------------

(defn- print-stash
  "Print stash to STDOUT"
  [stash classifiers winners]
  (let [width (count (:text (first stash)))
        winners-indices (map :index winners)]
    (doseq [[tok i] (reverse (map vector stash (iterate inc 0)))]
      (let [pos (:pos tok)
            end (:end tok)]
        (if pos
          (printf "%s %s%s%s %2d | %-9s | %-25s | P = %04.4f | %.20s\n"
                  (if (some #{(:index tok)} winners-indices) "W" " ")
                  (string/join (repeat pos \space))
                  (string/join (repeat (- end pos) \-))
                  (string/join (repeat (- width end -1) \space))
                  i
                  (when-let [x (:dim tok)] (name x))
                  (when-let [x (-> tok :rule :name)] (name x))
                  (float (learn/route-prob tok classifiers))
                  (string/join " + " (mapv #(get-in % [:rule :name]) (:route tok))))
          (printf "  %s\n" (:text tok)))))))

(defn- print-tokens
  "Recursively prints a tree representing a route"
  ([tokens classifiers]
   {:pre [(coll? tokens)]}
   (let [tokens (if (vector? tokens)
                  tokens
                  [tokens])
         tokens (if (= 1 (count tokens))
                  tokens
                  [{:route tokens :rule {:name "root"}}])]
     (print-tokens tokens classifiers 0)))
  ([tokens classifiers depth]
   (print-tokens tokens classifiers depth ""))
  ([tokens classifiers depth prefix]
   (doseq [[token i] (map vector tokens (iterate inc 1))]
     (let [;; determine name to display
           name (if-let [name (get-in token [:rule :name])]
                  name
                  (str "text: " (:text token)))
           p (learn/route-prob token classifiers)
           ;; prepare children prefix
           last? (= i (count tokens))
           new-prefix (if last? \space \|)
           new-prefix (str prefix new-prefix \space \space \space)]
       (when (pos? depth)
         (print (format "%s%s-- "
                        prefix
                        (if last? \` \|))))
       (println (format "%s (%s)" name p))
       (print-tokens (:route token)
                     classifiers
                     (inc depth)
                     (if (pos? depth) new-prefix ""))))))

(defn play
  "Show processing details for one sentence. Defines a 'details' function."
  ([module-id s]
   (play module-id s nil))
  ([module-id s targets]
   (play module-id s targets (default-context :corpus)))
  ([module-id s targets context]
   (let [targets (when targets (map (fn [dim] {:dim dim :label dim}) targets))
         {stash :stash
          winners :winners} (analyze s context module-id targets nil)]

     ;; 1. print stash
     (print-stash stash (get-classifier module-id) winners)

     ;; 2. print winners
     (printf "\n%d winners:\n" (count winners))
     (doseq [winner winners]
       (printf "%-25s %s %s\n" (str (name (:dim winner))
                                    (if (:latent winner) " (latent)" ""))
               (engine/export-value winner {:date-fn str})
               (dissoc winner :value :route :rule :pos :text :end :index
                       :dim :start :latent :body :pred :timezone :values)))

     ;; 3. ask for details
     (printf "For further info: (details idx) where 1 <= idx <= %d\n" (dec (count stash)))
     (defn details [n] (print-tokens (nth stash n) (get-classifier module-id)))
     (defn token [n] (nth stash n)))))

;;--------------------------------------------------------------------------
;; Configuration loading
;;--------------------------------------------------------------------------

(defn- gen-config-for-lang
  "Generates the full config for a language from directory structure."
  [lang]
  (->> ["corpus" "rules"]
       (map (fn [dir]
              (let [files (->> (format "languages/%s/%s" lang dir)
                               ;; res/get-files
                               (remove #(clojure.string/starts-with? % "_"))
                               (map #(subs % 0 (- (count %) 4)))
                               sort
                               vec)]
                [(keyword dir) files])))
       (into {})))

(defn- gen-config-for-langs
  "Generates the full config for langs from directory structure.

  Args:
  langs (vector): ISO language names

  Returns:
  (map): the config map"
  [langs]
  (->> langs
       (map (fn [lang]
              [(keyword (format "%s$core" lang)) (gen-config-for-lang lang)]))
       (into {})))

(deftest gen-config-for-langs-test
  (let [config (gen-config-for-langs ["ro" "tr"])]
    (is (nil? (s/explain-data :config/config config)))
    (is (= [:ro$core :tr$core] (keys config)))))

(defn- read-rules
  [lang new-file]
  (-> (format "languages/%s/rules/%s.clj" lang new-file)
      io/resource
      slurp
      read-string
      engine/rules))

(defn- read-corpus
  [lang new-file]
  (-> (format "languages/%s/corpus/%s.clj" lang new-file)
      io/resource
      ;; corpus/read-corpus
      ))

(defn- make-corpus
  "Make a corpus map

  Args:
  lang (string): ISO language name
  corpus-file (vector): filenames (dimensions) names

  Returns:
  (map): the corpus map {:context :tests}"
  [lang corpus-files]
  (->> corpus-files
       (pmap (partial read-corpus lang))
       (reduce (partial util/merge-according-to {:tests concat :context merge}))))

(deftest make-corpus-test
  (let [c (make-corpus "ro" ["temperature"])]
    (is (s/valid? :corpus/corpus c))))

(defn- make-rules
  "Make a rules vector

  Args:
  lang (string): ISO language name
  rules-file (vector): filenames (dimensions) names

  Returns:
  (vector): the rules map {:name :pattern :production} vector"
  [lang rules-files]
  (->> rules-files
       (pmap (partial read-rules lang))
       (apply concat)))

(deftest make-rules-test
  (is (nil? (s/explain-data :rule/rules (make-rules "ro" ["temperature"])))))

(defn- get-dims-for-test
  [context module {:keys [text]}]
  (let [logger (get context :logger (util/get-default-logger))]
    (mapcat (fn [text]
            (try
              (->> (analyze text context module nil nil)
                   :stash
                   (keep :dim))
              (catch Exception e
                (log logger :warn ::get-dims-fot-test {:module module :context context :text text})
                [])))
          text)))

(defn get-dims
  "Retrieves all available dimensions for module by running its corpus."
  [module {:keys [context tests]}]
  (->> tests
       (pmap (partial get-dims-for-test context module))
       (apply concat)
       distinct
       sort))

(defn load!
  "(Re)loads rules and classifiers for languages or/and config.

   Args:
   langsconfig (map): (optional) languages map with 3 keywords (:languages :config :logger).
                      If no language list nor config provided, loads all languages and use default logger.

   Returns:
   (map): loaded modules with available dimensions.
  "
  ([] (load! nil))
  ([{:keys [languages config logger] :or {logger (util/get-default-logger)}}]
   (let [langs (seq languages)
         lang-config (when (or langs (empty? config))
                       (cond->  (set #{}) ;; (set (res/get-subdirs "languages"))
                         langs (set/intersection (set langs))
                         true gen-config-for-langs))
         config (merge lang-config config)]
     (log logger :debug ::load  {:config config})
     (reset! rules-map {})
     (reset! corpus-map {})
     (let [data (->> config
                     (pmap (fn [[config-key {corpus-files :corpus rules-files :rules}]]
                             (let [lang (-> config-key
                                            name
                                            (string/split #"\$")
                                            first)
                                   corpus (make-corpus lang corpus-files)
                                   rules (make-rules lang rules-files)
                                   c (learn/train-classifiers corpus rules learn/extract-route-features)]
                               [config-key {:corpus corpus :rules rules :classifier c}])))
                     (into {}))]
       (doseq [[config-key {:keys [classifier corpus rules]}] data]
         (swap! corpus-map assoc config-key corpus)
         (swap! rules-map assoc config-key rules)
         (swap! classifiers-map assoc config-key classifier)))
     (->> @corpus-map
          (pmap (fn [[module corpus]]
                  [module (get-dims module corpus)]))
          (into {})))))

(deftest load!-test
  (let [config (load! {:languages ["ro" "en"]})]
    (is (nil? (s/explain-data :gen/load-result config)))
    (is (= [:en$core :ro$core] (keys config)))))

;;--------------------------------------------------------------------------
;; Corpus running
;;--------------------------------------------------------------------------

(defn run-corpus
  "Run the corpus given in parameter for the given module.
  Returns a list of vectors [0|1 text error-msg]"
  [{context :context, tests :tests} module]
  (for [test tests
        text (:text test)]
    (try
      (let [{:keys [stash winners]} (analyze text context module nil nil)
            winner-count (count winners)
            check (first (:checks test)) ; only one test is supported now
            check-results (map (partial check context) winners)] ; return nil if OK, [expected actual] if not OK
        (if (some #(or (nil? %) (false? %)) check-results)
          [0 text nil]
          [1 text [(ffirst check-results) (map second check-results)]]))
      (catch Exception e
        [1 text (.getMessage e)]))))

(defn run
  "Runs the corpus and prints the results to the terminal."
  ([]
   (run (keys @corpus-map)))
  ([module-id]
   (loop [[md & more] (if (seq? module-id) module-id [module-id])
          line 0
          acc []]
     (if md
       (let [output (run-corpus (md @corpus-map) md)
             failed (remove (comp (partial = 0) first) output)]
         (doseq [[[error-count text error-msg] i] (map vector failed (iterate inc line))]
           (printf "%d FAIL \"%s\"\n    Expected %s\n" i text (first error-msg))
           (doseq [got (second error-msg)]
             (printf "    Got      %s\n" got)))
         (printf "%s: %d examples, %d failed.\n" md (count output) (count failed))
         (recur more (+ line (count failed)) (concat acc (map (fn [[_ t _]] [md t]) failed))))
       (defn c [n]
         (let [[md text] (nth acc n)]
           (printf "(play %s \"%s\")\n" md text)
           (play md text)))))))

;;--------------------------------------------------------------------------
;; Public API
;;--------------------------------------------------------------------------

(defn parse
  "Public API. Parses text using given module.

  Args:
  module (string): the language module (ex en$core)
  text (string): the text to parse
  dims (list): (optional)  list of keywords referencing token dimensions to be extracted (default [] all dimensions)
  context (map): (optional) a map with a :reference-time key. If not provided, the system current date and time is used.

  Returns:
  (map): the tokens
  "
  ([module text]
   (parse module text []))
  ([module text dims]
   (parse module text dims (default-context :now)))
  ([module text dims context]
   (let [logger (get context :logger (util/get-default-logger))] 
     (log logger :debug ::parse {:module module, :dims dims, :text text})
     (->> (analyze text context module (map (fn [dim] {:dim dim :label dim}) dims) nil)
          :winners
          (map #(assoc % :value (engine/export-value % {})))
          (map #(select-keys % [:dim :body :value :start :end :latent]))
          distinct))))

;;--------------------------------------------------------------------------
;; The stuff below is specific to Wit.ai and will be moved out of Duckling
;;--------------------------------------------------------------------------

;; (defn- generate-context
;;   "Wit.ai internal. Will move to Wit."
;;   [base-context]
;;   (p/?> base-context
;;         (instance? org.joda.time.DateTime (:reference-time base-context))
;;         (assoc :reference-time {:start (:reference-time base-context)
;;                                 :grain :second})))

;; (defn extract
;;   "API used by Wit.ai (will be moved to Wit)
;;    targets is a coll of maps {:module :dim :label} for instance:
;;    {:module fr$core, :dim duration, :label wit$duration} to get duration results
;;    Returns a single coll of tokens with :body :value :start :end :label (=wisp) :latent"
;;   [sentence context leven-stash targets]
;;   {:pre [(string? sentence)
;;          (map? context)
;;          (:reference-time context)
;;          (vector? targets)]}
;;   (let [logger (get context :logger (util/get-default-logger))]
;;     (try
;;     (log logger :info ::extract  {:sentence sentence :targets targets})
;;     (letfn [(extract'
;;               [module targets] ; targets specify all the dims we should extract
;;               (let [module (keyword module)
;;                     pic-context (generate-context context)]
;;                 (when-not (module @rules-map)
;;                   (throw (ex-info "Unknown duckling module" {:module module})))
;;                 (->> (analyze sentence pic-context module targets leven-stash)
;;                      :winners
;;                      (map #(assoc % :value (engine/export-value % {:date-fn str})))
;;                      (map #(select-keys % [:label :body :value :start :end :latent])))))]
;;       (->> targets
;;            (group-by :module) ; we want to run each config only once
;;            (mapcat (fn [[module targets]] (extract' module targets)))
;;            vec))
;;     (catch Exception e
;;       (let [err {:e e
;;                  :sentence sentence
;;                  :context context
;;                  :leven-stash leven-stash
;;                  :targets targets}]
;;         (log logger :error ::extract-error err)
;;         [])))))
