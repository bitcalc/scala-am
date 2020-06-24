;; Parallel quick-sort
(define (build-vector n init f)
  (letrec ((v (make-vector n init))
           (loop (lambda (i)
                   (if (< i n)
                       (begin
                         (vector-set! v i (f i))
                         (loop (+ i 1)))
                       v))))
    (loop 0)))

(define N (int-top))
(define array-to-sort
  (build-vector N #f (lambda (i) (t/ref (random 100)))))

(define (swap array i j)
  (let* ((ref1 (vector-ref array i))
         (ref2 (vector-ref array j))
         (tmp (t/deref ref1)))
    (t/ref-set ref1 (t/deref ref2))
    (t/ref-set ref2 tmp)))

(define (partition array lo hi)
  (let ((pivot (t/deref (vector-ref array hi))))
    (letrec ((loop (lambda (j i)
                     (if (= j hi)
                         i
                         (if (< (t/deref (vector-ref array j)) pivot)
                             (begin
                               (swap array (+ i 1) j)
                               (loop (+ j 1) (+ i 1)))
                             (loop (+ j 1) i))))))
      (let ((i (loop lo (- lo 1))))
        (if (< (t/deref (vector-ref array hi))
               (t/deref (vector-ref array (+ i 1))))
            (swap array (+ i 1) hi)
            #t)
        (+ i 1)))))

(define (quicksort array left right)
  (let ((pivot left))
    (if (<= right left)
        ;; do nothing
        #t
        ;; sort
        (let* ((new-pivot (partition array left right))
               (tleft (fork (quicksort array left (- new-pivot 1))))
               (tright (fork (quicksort array (+ new-pivot 1) right))))
          (join tleft)
          (join tright)
          #t))))

(define (range a b)
  (letrec ((loop (lambda (i acc)
                   (if (< i a)
                       acc
                       (loop (- i 1) (cons i acc))))))
    (loop (- b 1) '())))

(define (display-array array)
  (for-each (lambda (i)
              (display (t/deref (vector-ref array i)))
              (display " "))
            (range 0 (vector-length array)))
  (newline))

(define (qsort array)
  (display-array array)
  (quicksort array 0 (- (vector-length array) 1))
  (display-array array))

(qsort array-to-sort)