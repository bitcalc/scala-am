(define (create-queue)
  (let ((front '())
        (rear '()))
    (define (empty?)
      (null? front))
    (define (enqueue element-list)
      (if (null? element-list)
          #t
          (begin
            (cond
              ((null? front)
               (set! front (list (car element-list)))
               (set! rear front))
              (else
                (set-cdr! rear (list (car element-list)))
                (set! rear (cdr rear))))
            (enqueue (cdr element-list)))))
    (define (dequeue)
      (if (null? front)
          (error "Can't front. The queue is empty.")
          (let ((temp (car front)))
            (set! front (cdr front))
            temp)))
    (define (serve)
      (if (null? front)
          (error "Can't serve. The queue is empty.")
          (car front)))
    (define (dispatch msg . args)
      (cond
        ((eq? msg 'empty?) (empty?))
        ((eq? msg 'enqueue) (enqueue args))
        ((eq? msg 'dequeue) (dequeue))
        ((eq? msg 'serve) (serve))
        (else
          (error "unknown request -- create-queue" msg))))
    dispatch))