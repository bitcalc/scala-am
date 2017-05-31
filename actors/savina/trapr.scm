(define NumWorkers (int-top))
(define Precision (int-top))
(define L (int-top))
(define R (int-top))

(define (exp x)
  (expt 2.718281828459045 x))

(define (fx x)
  (let* ((a (sin (- (expt x 3) 1)))
         (b (+ x 1))
         (c (/ a b))
         (d (sqrt (+ 1 (exp (sqrt (* 2 x))))))
         (r (* c d)))
    r))

(define (compute-area l r h)
  (let ((n (/ (- r l) h)))
    (letrec ((loop (lambda (i acc)
                     (if (= i n)
                         acc
                         (let* ((lx (+ (* i h) l))
                                (rx (+ lx h))
                                (ly (fx lx))
                                (ry (fx rx)))
                           (loop (+ i 1) (+ acc (* 0.5 (+ ly ry) h))))))))
      (loop 0 0))))

(define (build-vector n f)
  (letrec ((v (make-vector n #f))
           (loop (lambda (i)
                   (if (< i n)
                       (begin
                         (vector-set! v i (f i))
                         (loop (+ i 1)))
                       v))))
    (loop 0)))

(define master-actor
  (a/actor "master-actor" (workers terms-received result-area)
            (result (v id)
                    (if (= (+ terms-received 1) NumWorkers)
                        (a/terminate)
                        (a/become master-actor workers (+ terms-received 1) (+ result-area v))))
            (work (l r h)
                  (let ((range (/ (- r l) NumWorkers)))
                    (letrec ((loop (lambda (i)
                                     (if (= i NumWorkers)
                                         (a/become master-actor workers terms-received result-area)
                                         (let* ((wl (+ (* range i) l))
                                                (wr (+ wl range)))
                                           (a/send (vector-ref workers i) work wl wr h)
                                           (loop (+ i 1)))))))
                      (loop 0))))))
(define worker-actor
  (a/actor "worker-actor" (master id)
            (work (l r h)
                  (let ((area (compute-area l r h)))
                    (a/send master result area id)
                    (a/terminate)))))
(define master (a/create master-actor
                         (build-vector NumWorkers (lambda (i) (a/create worker-actor master i))) 0 0))
(a/send master work L R Precision)
