(define (make-flip)
  (let ((state 0))
    (lambda ()
      (if (= state 0)
          (set! state 1)
          (set! state 0))
      state)))

(define flip (make-flip))

(and (= (flip) 1)
     (= (flip) 0)
     (= (flip) 1)
     (= (flip) 0))