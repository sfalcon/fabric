(ns thi.ng.fabric.core
  (:require
   [thi.ng.fabric.utils :as fu]))

(defprotocol PComputeGraph
  (add-vertex [_ vspec])
  (add-edge [_ a b espec])
  (vertex-for-id [_ id])
  (execute [_ opts])
  (vertices [_]))

(defrecord Vertex
    [id state prev
     out uncollected
     collect score-sig score-coll
     mod-sig mod-collect])

(defrecord Edge
    [src target signal weight sig-map?])

(defn default-score-signal
  [{:keys [state prev mod-since-sig]}]
  (if mod-since-sig
    1
    (if prev
      (if (= state prev) 0 1)
      1)))

(defn default-score-collect
  [v]
  (+ (count (:uncollected v)) (if (:mod-since-collect v) 1 0)))

(defn signal-forward [e v] (:state v))

(defn collect-union
  [v sig] (update v :state into sig))

(defn vertex
  [id {:keys [state collect score-sig score-coll]}]
  (atom
   (Vertex.
    id state nil
    #{} nil
    collect
    (or score-sig default-score-signal)
    (or score-coll default-score-collect)
    false
    false)))

(defn edge
  [src-vertex target-vertex {:keys [weight signal sig-map]}]
  (let [e (Edge. (:id @src-vertex) (:id @target-vertex) signal (or weight 1) sig-map)]
    (when-not ((:out @src-vertex) e)
      (swap!
       src-vertex
       #(-> %
            (update :out conj e)
            (assoc :mod-sig true :mod-collect true))))
    e))

(defn do-signal
  [v vertices]
  (let [v' @v]
    (swap! v assoc :prev (:state v') :mod-sig false)
    (doseq [{:keys [src] :as e} (:out v')]
      (swap!
       (vertices (:target e))
       (fn [t]
         (if-let [sigv ((:signal e) e v')]
           (let [t (update t :uncollected conj sigv)]
             (if (:sig-map? e)
               (update t :signals assoc (:src e) sigv)
               t))
           t))))))

(defn do-collect
  [v _]
  (swap!
   v #(reduce
       (fn [v sig] ((:collect v) v sig))
       (assoc % :uncollected nil :mod-collect false)
       (:uncollected %))))

(defn sc-phase
  [phase-fn score thresh verts done]
  (reduce
   (fn [done v]
     (let [v' @v
           score ((score v') v')
           ;;_ (fu/trace-v :coll-1 v score)
           done (if (> score thresh)
                  (do (phase-fn v verts) false)
                  done)]
       ;;(fu/trace-v :coll-2 v score)
       done))
   done (vals verts)))

(deftype SyncGraph
    [state]

  #+clj  clojure.lang.IDeref
  #+clj  (deref [_] (deref state))
  #+cljs IDeref
  #+cljs (-deref [_] (deref state))

  PComputeGraph
  (add-vertex
    [_ vspec]
    (let [id (:next-id @state)
          v  (vertex id vspec)]
      (swap! state #(-> % (update :vertices assoc id v) (assoc :next-id (inc id))))
      v))
  (add-edge
    [_  a b espec]
    (edge a b espec))
  (vertices
    [_] (:vertices @state))
  (vertex-for-id
    [_ id] ((@state :vertices) id))
  (execute
    [_ {:keys [iter sig-thresh coll-thresh]
        :or {sig-thresh 0 coll-thresh 0}}]
    (loop [done false, i 0]
      (if done
        {:converged true :iter i}
        (if (< i iter)
          (let [verts (:vertices @state)
                done (sc-phase do-signal :score-sig sig-thresh verts true)
                done (sc-phase do-collect :score-coll coll-thresh verts done)]
            ;;(prn :collect-done done i (fu/dump _))
            (recur done (inc i)))
          {:converged false :iter i})))))

(defn graph
  []
  (SyncGraph.
   (atom
    {:vertices {}
     :next-id 0})))
