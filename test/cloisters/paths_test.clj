(ns cloisters.paths-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloisters.fixtures :as fx]
            [cloisters.paths :as paths]))

(defn- paths-of [dirs] (update-vals dirs str))

(deftest xdg-defaults
  (is (= {:config "/home/u/.config/cloisters"
          :data "/home/u/.local/share/cloisters"
          :state "/home/u/.local/state/cloisters"
          :cache "/home/u/.cache/cloisters"}
         (paths-of (paths/dirs {} "/home/u")))))

(deftest xdg-variables
  (testing "honoured when absolute"
    (is (= {:config "/x/config/cloisters" :data "/x/data/cloisters"
            :state "/x/state/cloisters" :cache "/x/cache/cloisters"}
           (paths-of (paths/dirs {"XDG_CONFIG_HOME" "/x/config" "XDG_DATA_HOME" "/x/data"
                                  "XDG_STATE_HOME" "/x/state" "XDG_CACHE_HOME" "/x/cache"}
                                 "/home/u")))))
  (testing "ignored when relative or empty, as the XDG spec requires"
    (is (= "/home/u/.local/share/cloisters"
           (str (:data (paths/dirs {"XDG_DATA_HOME" "relative/data"} "/home/u")))
           (str (:data (paths/dirs {"XDG_DATA_HOME" ""} "/home/u")))))))

(deftest cloisters-home
  (let [dirs (paths/dirs {"CLOISTERS_HOME" "/tmp/ch" "XDG_DATA_HOME" "/x/data"} "/home/u")]
    (testing "overrides the XDG variables and relocates everything"
      (is (= {:config "/tmp/ch/config" :data "/tmp/ch/data" :state "/tmp/ch/state" :cache "/tmp/ch/cache"}
             (paths-of dirs))))
    (testing "the files sit in the XDG layout under it"
      (is (= "/tmp/ch/config/config.edn" (str (paths/config-file dirs))))
      (is (= "/tmp/ch/data/cloisters.db" (str (paths/store-file dirs))))
      (is (= "/tmp/ch/data/backups" (str (paths/backups-dir dirs))))
      (is (= "/tmp/ch/data/daemon.lock" (str (paths/lock-file dirs))))
      (is (= "/tmp/ch/data/lib" (str (paths/lib-dir dirs))))
      (is (= "/tmp/ch/state/logs" (str (paths/logs-dir dirs))))))
  (testing "a relative CLOISTERS_HOME resolves against the working directory"
    (is (.isAbsolute (:data (paths/dirs {"CLOISTERS_HOME" ".cloisters-dev"} "/home/u"))))))

(defn- home-with-config [content]
  (let [home (fx/tmp-dir)
        dirs (paths/dirs {"CLOISTERS_HOME" (str home)} "/unused")]
    (when content
      (io/make-parents (paths/config-file dirs))
      (spit (paths/config-file dirs) content))
    dirs))

(deftest config
  (testing "defaults to port 7227 with no file"
    (is (= {:port 7227} (paths/config (home-with-config nil)))))
  (testing "reads config.edn over the defaults"
    (is (= {:port 7228} (paths/config (home-with-config "{:port 7228}")))))
  (testing "refuses an unusable port, naming the file and key"
    (let [e (try (paths/config (home-with-config "{:port \"7228\"}")) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (re-find #"config\.edn: :port must be a TCP port number" (ex-message e)))))
  (testing "refuses a file that is not one EDN map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must hold one map"
                          (paths/config (home-with-config "[:port 7228]"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not readable EDN"
                          (paths/config (home-with-config "{:port"))))))
