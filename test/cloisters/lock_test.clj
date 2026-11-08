(ns cloisters.lock-test
  "The lock is held by a process, so these tests run the daemon in a separate JVM, as a real second daemon would."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloisters.fixtures :as fx]
            [cloisters.lock :as lock])
  (:import (java.lang ProcessBuilder$Redirect)
           (java.util.concurrent TimeUnit)))

(defn- start-daemon
  "The daemon under home in its own JVM, its stdout discarded and its stderr kept for reading."
  ^Process [home]
  (let [java (str (io/file (System/getProperty "java.home") "bin" "java"))
        pb (doto (ProcessBuilder. ^java.util.List [java "--enable-native-access=ALL-UNNAMED"
                                                   "-cp" (System/getProperty "java.class.path")
                                                   "clojure.main" "-m" "cloisters.main"])
             (.redirectOutput ProcessBuilder$Redirect/DISCARD))]
    (.put (.environment pb) "CLOISTERS_HOME" (str home))
    (.start pb)))

(defn- lock-file [home] (io/file home "data" "daemon.lock"))

(deftest a-second-daemon-is-refused
  (let [home (fx/tmp-dir)
        f (lock-file home)
        held (lock/acquire! f {:port 7299 :version "first"})
        pid (.pid (java.lang.ProcessHandle/current))]
    (try
      (let [p (start-daemon home)
            err (future (slurp (.getErrorStream p)))]
        (is (.waitFor p 60 TimeUnit/SECONDS))
        (is (= 1 (.exitValue p)))
        (is (str/includes? @err (str "another daemon holds " f ": pid " pid ", port 7299, version first"))))
      (finally (lock/release! held)))
    (testing "release clears the record and leaves the file"
      (is (.exists f))
      (is (nil? (lock/holder f))))))

(deftest a-daemon-holds-the-lock-until-stopped
  (let [home (fx/tmp-dir)
        f (lock-file home)
        snapshot (io/file home "data" "backups" (str "daily-" (java.time.LocalDate/now) ".db"))
        p (start-daemon home)]
    (future (slurp (.getErrorStream p)))
    (try
      (loop [tries 600]
        (when (and (not (.exists snapshot)) (pos? tries) (.isAlive p))
          (Thread/sleep 100)
          (recur (dec tries))))
      (testing "the running daemon has taken its startup snapshot"
        (is (.exists snapshot)))
      (testing "the running daemon records its pid, port and version"
        (is (= {:pid (.pid p) :port 7227 :version "0.1.0-SNAPSHOT"} (lock/holder f))))
      (testing "a stop releases the lock and checkpoints the WAL"
        (.destroy p)
        (is (.waitFor p 30 TimeUnit/SECONDS))
        (is (nil? (lock/holder f)))
        (let [wal (io/file home "data" "cloisters.db-wal")]
          (is (or (not (.exists wal)) (zero? (.length wal))))))
      (finally (.destroyForcibly p)))))
