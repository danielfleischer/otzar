(ns rag.watch
  "Background auto-reindex — a periodic sweep of the persisted roots (rag.roots) so
  new, changed, and deleted files are picked up without a manual `otzer index`. The
  sweep itself lives in rag.index/sweep! (which is single-flighted against manual
  indexing); this namespace owns only the *scheduling* and the *toggle*:

    - a single-thread ScheduledExecutorService that fires sweep! on an interval;
    - an enabled/interval setting persisted to ~/.otzer/watch.edn, read at boot so
      the toggle survives a server restart;
    - the last sweep's timestamp + summary, for GET /watch.

  Off by default. Enabling arms the scheduler with the first sweep one interval out
  (not immediately), so toggling watch never itself triggers embedding work. A
  no-op sweep over an unchanged tree does not load the Ollama model (see sweep!),
  which is what makes a 30-minute cadence cheap on a laptop."
  (:require [clojure.edn :as edn]
            [rag.config :as config]
            [rag.index :as index])
  (:import [java.util.concurrent Executors ScheduledExecutorService
            ScheduledFuture ThreadFactory TimeUnit]))

(defonce ^:private state
  (atom {:enabled    false
         :interval-s config/watch-interval-s
         :scheduler  nil
         :future     nil
         :last       nil}))    ; {:at epoch-ms :result <sweep! summary>}

(defn- read-config
  "Persisted {:enabled bool :interval-s long} from watch.edn, or nil if absent/
  unreadable (a corrupt file degrades to defaults, it doesn't crash boot)."
  []
  (try
    (when (.exists config/watch-file)
      (edn/read-string (slurp config/watch-file)))
    (catch Exception _ nil)))

(defn- write-config! []
  (config/ensure-dir!)
  (let [{:keys [enabled interval-s]} @state]
    (spit config/watch-file (pr-str {:enabled (boolean enabled) :interval-s interval-s}))))

(defn- run-sweep!
  "Run one sweep, catching everything (a thrown task cancels a fixed-rate schedule),
  and record its outcome for GET /watch."
  []
  (let [result (try (index/sweep!)
                    (catch Throwable t {:error (.getMessage t)}))]
    (swap! state assoc :last {:at (System/currentTimeMillis) :result result})))

(def ^:private thread-factory
  (reify ThreadFactory
    (newThread [_ r] (doto (Thread. r "otzer-watch") (.setDaemon true)))))

(defn- disarm!
  "Cancel the running schedule (if any) and drop the executor."
  []
  (let [{:keys [^ScheduledFuture future ^ScheduledExecutorService scheduler]} @state]
    (when future (.cancel future false))
    (when scheduler (.shutdownNow scheduler))
    (swap! state assoc :scheduler nil :future nil)))

(defn- arm!
  "Start a fresh scheduler firing sweeps every `interval-s`, the first one one
  interval from now (so enabling never itself kicks off embedding work)."
  [interval-s]
  (let [sched (Executors/newSingleThreadScheduledExecutor thread-factory)
        fut   (.scheduleAtFixedRate sched ^Runnable run-sweep!
                                    (long interval-s) (long interval-s) TimeUnit/SECONDS)]
    (swap! state assoc :scheduler sched :future fut)))

(defn status
  "-> map
  Current watch state for GET /watch: whether the sweep is armed, its interval,
  whether an index run is in progress right now, and the last sweep's time+summary."
  []
  (let [{:keys [enabled interval-s last]} @state]
    (cond-> {:enabled    (boolean enabled)
             :interval_s interval-s
             :indexing   (index/indexing?)}
      last (assoc :last_sweep_at (:at last)
                  :last_result   (:result last)))))

(defn enable!
  "interval-s:long? -> status
  Arm the background sweep, optionally changing the interval (nil keeps the current
  one), and persist the toggle. Re-arms cleanly if already on. The first sweep fires
  one interval from now."
  [interval-s]
  (let [iv (or interval-s (:interval-s @state))]
    (disarm!)
    (swap! state assoc :enabled true :interval-s iv)
    (arm! iv)
    (write-config!)
    (status)))

(defn disable!
  "-> status
  Turn the sweep off (cancelling any schedule) and persist it. The server keeps
  running; a sweep already mid-run finishes on its own."
  []
  (disarm!)
  (swap! state assoc :enabled false)
  (write-config!)
  (status))

(defn init!
  "-> nil
  Load the persisted toggle at boot (rag.main) and arm the sweep if it was left
  enabled. A per-user interval in watch.edn takes precedence over the config
  default. No-op when watch was off (the common case)."
  []
  (let [{:keys [enabled interval-s]} (read-config)]
    (when interval-s (swap! state assoc :interval-s interval-s))
    (when enabled (enable! interval-s))
    nil))
