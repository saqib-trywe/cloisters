(ns cloisters.lock
  "The daemon lock: an exclusive OS lock on daemon.lock beside the store, held for the daemon's lifetime and recording
  its pid, port and version, so a second daemon on the same store is refused, naming the holder.

  OS file locks belong to the process: closing any other handle on the file in the holding process can release
  them. The daemon takes the lock once and never otherwise opens the file."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.io File RandomAccessFile)
           (java.nio.channels FileLock OverlappingFileLockException)
           (java.nio.charset StandardCharsets)))

(defn- parse [s]
  (let [s (str/trim s)]
    (when-not (str/blank? s)
      (try (edn/read-string s) (catch Exception _ nil)))))

(defn holder
  "What f records about the daemon holding it, or nil. For other processes, such as `daemon status`."
  [^File f]
  (when (.exists f) (parse (slurp f))))

(defn- refusal [^File f ^RandomAccessFile raf]
  (let [bytes (byte-array (.length raf))
        _ (.readFully raf bytes)
        {:keys [pid port version] :as held} (parse (String. bytes StandardCharsets/UTF_8))]
    (ex-info (str "another daemon holds " f (when pid (str ": pid " pid ", port " port ", version " version)))
             {:lock (str f) :holder held})))

(defn acquire!
  "Takes the lock on f for this process and records pid, port and version in it, or throws naming the holder."
  [^File f {:keys [port version]}]
  (.mkdirs (.getParentFile f))
  (let [raf (RandomAccessFile. f "rw")
        lock (try (.tryLock (.getChannel raf)) (catch OverlappingFileLockException _ nil))]
    (if lock
      (do (.setLength raf 0)
          (.write raf (.getBytes (pr-str {:pid (.pid (java.lang.ProcessHandle/current)) :port port :version version})
                                 StandardCharsets/UTF_8))
          {:file f :raf raf :lock lock})
      (let [e (refusal f raf)]
        (.close raf)
        (throw e)))))

(defn release!
  "Clears the record and releases the lock. The file stays, so no other daemon can lock a file about to vanish."
  [{:keys [^RandomAccessFile raf ^FileLock lock]}]
  (.setLength raf 0)
  (.release lock)
  (.close raf))
