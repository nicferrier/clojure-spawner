
(require 'elnode)

(setq spawner/commands
      '((:start :doit2)
        (:status)))

(setq spawner/commands
      '((:status)))

(setq spawner/commands
      '((:exit)))

(defun spawner-handler (httpcon)
  (message "data: %S" (elnode-http-params httpcon))
  (elnode-http-start httpcon 200 '(content-type "text/lisp"))
  (elnode-http-return httpcon (format "%S" (pop spawner/commands))))

(elnode-start 'spawner-handler :port 6001)
