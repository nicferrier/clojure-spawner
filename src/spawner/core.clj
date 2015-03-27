(ns spawner.core
  "A spawning process controlller.

Like a process supervisor.  This uses core.async extensively."
  (:require
   [clojure.core.async
    :refer [>! <! >!! <!! go go-loop chan close!  alts! alts!! timeout thread]])
  (:require [clojure.string :as str])
  (:require [clojure.java.io :as io])
  (:require [org.httpkit.client :as http-kit])
  (:gen-class))

(def scripts
  "The list of scripts we'll start."
  { :doit ["bash" "doit.sh"]
   :doit2 ["bash" "doit2.sh"]})

(defn start-process
  "Start the process with script-defn." [id script-defn]
  (let [proc (.start (ProcessBuilder. script-defn))
        from (chan)
        to (chan)]
    ;; Make a thread to wait for the exit
    (thread (>!! from [:exit id (.waitFor proc)]))

    ;; Make a thread to read the inputstream ... 
    (thread
     (binding [*in* (io/reader (.getInputStream proc))]
       (loop [line (read-line)]
         (when line
           (>!! from [:line id line])
           (recur (read-line))))))

    ;; ... and another to process incomming messages.
    (thread
     ;; This might have to be changed, what if a blocked out-stream
     ;; stopped us getting a kill signal from the channel?
     (loop [[message arg] (<!! to)]
       (case message
         :line (binding [*out* (io/reader (.getOutputStream proc))] (println arg))
         :kill (.destroy proc))
       (recur (<!! to))))

    ;; Return the info
    { :from from :to to :id id :proc proc }))

(defn call-http
  "Call the monitor and report status.

HTTP requests are made to monitor-url and the response is returned to
the request-chan. Any message on the response-chan (presumably a
response to the last request) is sent in the next call."
  [monitor-url request-chan response-chan]
  (loop [[to-send ch] (alts!! [response-chan (timeout 500)])]
    (when (or (not to-send) (not (= to-send [:quit])))
      (http-kit/post
       monitor-url
       { :query-params (when to-send { :data (print-str to-send) }) :timeout 400 }
       (fn [{:keys [status headers body error]}]
         (if error
           (>!! request-chan [:http-error error [status headers]])
           (>!! request-chan (read-string body)))))
      (recur (alts!! [response-chan (timeout 500)])))))

(defn call-http-test
  "Wrap call-http with a test frame.

Stops http calls actually happening but replaces them with similar
async behaviour."  [monitor-url request-chan response-chan]
  (let [command-chan (chan)]
    (go
     (>! command-chan [:start :doit2])
     (>! command-chan [:noop])
     (>! command-chan [:start :doit])
     (>! command-chan [:start :nothere])
     (>! command-chan [:status])
     (>! command-chan [:exit]))
    (with-redefs
      [http-kit/post
       (fn [url options callback]
         (let [[command & args] (<!! command-chan)]
           (when args (println "command " command " the args were: " args))
           (println ">> the url is " url " options " options " and the command " command)
           (Thread/sleep (options :timeout))
           (>!! request-chan (if args
                               (apply conj [command] args)
                               [command]))))]
      (call-http monitor-url request-chan response-chan))))

(defn -main
  "Main for the spawner." [& args] ; we could take a monitor url
  (let [input (chan)
        output (chan)
        started (ref {})]

    ;; read messages from the command input
    (go-loop 
     [[message & args] (<! input)]
     (let [response ; capture the response from processing the message
           (case message
             :start (let [[id & rest] args]
                      ;; Could add a test for id == :all here
                      (if (scripts id)
                        (dosync
                         (alter started assoc id (start-process id (scripts id))))
                        ;; Else there's an error
                        [:error :notfound]))
             :deploy (let [[id & rest] args] (println "redeploy " id " " args))
             :kill (let [[id & rest] args]
                     (when (@started args)
                       (>! ((@started args) :from) :kill)))
             :noop [:noop-received]
             :status [:status (map #(% " started " (scripts %)) (keys @started))]
             :http-error [:http-error "whoops! http error!"]
             :exit (do (println "dieing!") [:quit])
             true [:http-error "whoops! that's not a signal we understand"])]
       (>! output (if response response []))) ; and send response so we recur http
     (recur (<! input)))
     
    ;; read the signals from the started processes
    (go-loop
     [channels (keep #(% :from) (vals @started))]
     (let [[[message id args] ch] (alts! (conj channels (timeout 100)))]
       (case message
         :line (println "process " id " read " message " with args: " args)
         :exit (dosync (alter started dissoc id) (println id " exited"))
         nil)
       (recur (keep #(% :from) (vals @started)))))

    ;; Setup the http loop - this is the test mode.
    (call-http-test "http://localhost:6001" input output)))

;; spawner ends here
