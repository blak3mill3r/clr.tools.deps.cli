(ns clojure.tools.deps.cli.test-api
  (:require
    #?(:clj [clojure.java.io :as jio]
	   :cljr [clojure.clr.io :as cio])
    [clojure.string :as str]
    [clojure.test :refer [deftest is] :as test]
    [clojure.tools.deps.cli.api :as api]
    [clojure.tools.deps] ;; ensure extensions loaded
    #?(:clj [clojure.tools.deps.util.maven :as mvn])
    [clojure.tools.deps.util.dir :as dir])
  (:import
    #?(:clj [java.io File]
	   :cljr [System.IO DirectoryInfo])))

(def ^:dynamic ^#?(:clj File :cljr DirectoryInfo) *test-dir*)

(defmacro with-test-dir
  [& body]
  `(let [name# (-> test/*testing-vars* last symbol str)
         dir# (#?(:clj jio/file :cljr cio/dir-info) "test-out" name#)]
     (#?(:clj .delete :cljr .Delete) dir#)
     (#?(:clj .mkdirs :cljr .Create) dir#)
     (binding [*test-dir* dir#]
       ~@body)))

(deftest test-prep-with-aliases
  (with-test-dir
    (let [p1deps (#?(:clj jio/file  :cljr cio/file-info) *test-dir* "p1/deps.edn")
          p2deps (#?(:clj jio/file  :cljr cio/file-info) *test-dir* "p2/deps.edn")]

      ;; set up p1 with an alias that, if used, pulls p2
      #?(:clj (jio/make-parents p1deps)
	     :cljr (.Create (.Directory p1deps)))
      (spit p1deps
            (pr-str {:aliases {:x {:extra-deps {'foo/bar {:local/root "../p2"}}}}}))

      ;; set up p2 to prep
      #?(:clj (jio/make-parents p2deps)
	     :cljr (.Create (.Directory p2deps)))
      #?(:clj (spit (jio/file *test-dir* "p2/build.clj")
                    "(ns build
                       (:require [clojure.java.io :as jio]))
                     (defn prep [_]
                       (jio/make-parents \"prepped/out\"))")
		 :cljr (spit (cio/file-info *test-dir*  "p2/build.clj")
	                    "(ns build
                       (:require [clojure.clr.io :as cio]))
                     (defn prep [_]
                       (.Create (cio/dir-info \"prepped/out\")))")
        )					   
      (spit p2deps
            (pr-str {:deps/prep-lib {:ensure "prepped"
                                     :alias :build
                                     :fn 'prep}
                     :aliases {:build {:ns-default 'build}}}))

      ;; prep p1 with aliases
      (dir/with-dir #?(:clj (jio/file *test-dir* "p1")
	                   :cljr (cio/file-info *test-dir* "p1"))
        (api/prep
         {#?(:clj :root) #?(:clj {:mvn/repos mvn/standard-repos})
          :user nil
          :project :standard
          :aliases [:x]
          :force true}))

      ;; check that it prepped p2
      #?(:clj (is (true? (.exists (jio/file *test-dir* "p2/prepped"))))
	     :cljr (is (true? (.Exists (cio/file-info *test-dir* "p2/prepped")))))
	  
	  )))


(deftest test-prep-across-modules
  (with-test-dir
    (spit (jio/file *test-dir* "deps.edn")
      (pr-str {:deps {'mono/moda {:local/root "mod/a"}
                      'mono/modb {:local/root "mod/b"}}}))
    (let [adeps (jio/file *test-dir* "mod/a/deps.edn")]
      (jio/make-parents adeps)
      (spit adeps "{}"))
    (let [bdeps (jio/file *test-dir* "mod/b/deps.edn")]
      (jio/make-parents bdeps)
      (spit bdeps
        (pr-str {:paths ["src"]
                 :deps/prep-lib {:alias :resources
                                 :fn 'bcore/generate
                                 :ensure "target/resources"}
                 :aliases {:resources {:deps {'mono/moda {:local/root "../a"}} :paths ["src"]}}})))
    (let [bgen (jio/file *test-dir* "mod/b/src/bcore.clj")
          cp-path (.getCanonicalPath (jio/file *test-dir* "mod/b/target/resources/cp"))]
      (jio/make-parents bgen)
      (spit bgen
        (str
          "(ns bcore)"
          (format "(defn generate [_] (.mkdirs (.getParentFile (java.io.File. \"%s\"))) (spit \"%s\" (System/getProperty \"java.class.path\")))"
            cp-path cp-path)))
      (api/prep
        {:root {:mvn/repos mvn/standard-repos}
         :user nil
         :project {:deps {'org.clojure/clojure {:mvn/version "1.11.1"}
                          'mono/root {:local/root (.getPath *test-dir*)}}}
         ;; :log :debug
         :force true})
      (let [cp-out (slurp cp-path)]
        (is (true? (str/includes? cp-out (.getCanonicalPath (jio/file *test-dir* "mod/a/src")))))))))

(deftest test-prep-exec-args
  (with-test-dir
    (let [p1deps (jio/file *test-dir* "p1/deps.edn")
          p2deps (jio/file *test-dir* "p2/deps.edn")]

      ;; set up p1 to depend on p2
      (jio/make-parents p1deps)
      (spit p1deps (pr-str {:deps {'foo/p2 {:local/root "../p2"}}}))

      ;; set up p2 to prep
      (jio/make-parents p2deps)
      (spit (jio/file *test-dir* "p2/build.clj")
            "(ns build
               (:require [clojure.java.io :as jio]))
             (defn prep [args]
               (jio/make-parents \"prepped/out\")
               (spit \"prepped/out\" args))")
      (spit p2deps
            (pr-str {:deps/prep-lib {:ensure "prepped"
                                     :alias :build
                                     :fn 'prep}
                     :aliases {:build {:ns-default 'build
                                       :exec-args {:hi :there}}}}))

      ;; prep p1 with aliases
      (dir/with-dir (jio/file *test-dir* "p1")
        (api/prep
         {:root {:mvn/repos mvn/standard-repos}
          :user nil
          :project :standard
          :force true}))

      ;; check that it prepped p2 and received the args
      (is (= "{:hi :there}" (slurp (jio/file *test-dir* "p2/prepped/out")))))))

(deftest test-self-prep
  (with-test-dir
    (let [p1deps (jio/file *test-dir* "p1/deps.edn")
          p2deps (jio/file *test-dir* "p2/deps.edn")]

      ;; set up p1 to depend on p2 but also need self prep
      (jio/make-parents p1deps)
      (spit (jio/file *test-dir* "p1/build.clj")
            "(ns build
               (:require [clojure.java.io :as jio]))
             (defn prep [args]
               (jio/make-parents \"prepped/out\"))")
      (spit p1deps
            (pr-str {:deps {'foo/p2 {:local/root "../p2"}}
                     :deps/prep-lib {:ensure "prepped"
                                     :alias :build
                                     :fn 'prep}
                     :aliases {:build {:ns-default 'build}}}))

      ;; set up p2 to prep
      (jio/make-parents p2deps)
      (spit (jio/file *test-dir* "p2/build.clj")
            "(ns build
               (:require [clojure.java.io :as jio]))
             (defn prep [args]
               (jio/make-parents \"prepped/out\"))")
      (spit p2deps
            (pr-str {:deps/prep-lib {:ensure "prepped"
                                     :alias :build
                                     :fn 'prep}
                     :aliases {:build {:ns-default 'build}}}))

      ;; prep p1
      (dir/with-dir (jio/file *test-dir* "p1")
        (api/prep
         {:root {:mvn/repos mvn/standard-repos}
          :user nil
          :project :standard
          ;; :log :debug
          :force true
          :current true}))

      ;; check that it prepped p1 and p2
      (is (true? (.exists (jio/file *test-dir* "p1/prepped"))))
      (is (true? (.exists (jio/file *test-dir* "p2/prepped")))))))

(deftest test-find-maven-version
  (let [s (with-out-str (api/find-versions {:lib 'org.clojure/clojure :n :all}))]
    (is (str/includes? s "1.10.3")))

  (is (= "" (with-out-str (api/find-versions {:lib 'bogus.taco/slurpee})))))

(deftest test-find-git-version
  (let [s (with-out-str (api/find-versions {:lib 'io.github.clojure/tools.build :n :all}))]
    (is (str/includes? s "v0.8.2")))

  (is (= "" (with-out-str (api/find-versions {:lib 'io.github.clojure/bogus-taco-slurpee})))))

(comment
  (test-find-maven-version)
  (test-find-git-version)
  )