# Yielder.java

A simple producer / consumer class for Java, supporting the `yield` / generator pattern.

## Semantics

* `Yielder` launches separate threads for the `Producer` and `Consumer`, and sets up a bounded blocking FIFO queue between the two threads.
* The `Producer` blocks when the queue is full, the `Consumer` blocks when the queue is empty.
* The `Producer` is passed a `Receiver` object, which it may `yield(T)` objects of type `<T>` to, to send them to the `Consumer` via the FIFO queue.
* The `Consumer` has a `consume(T)` object that is called for each item produced by the `Producer`.
* When the `Producer` exits, an end of queue marker is sent to the `Consumer`, and the `Producer` thread shuts down.
* When the `Consumer` has consumed all items, and reaches the end of queue marker, the `Consumer` thread is shut down.
* The `close()` method of `Yielder` blocks on both the `Producer` and `Consumer` finishing their work and shutting down.

## Caveats

* If the `Producer` or `Consumer` throws an unchecked exception, then the `close()` method of `Yielder` will wrap the exception with a `RuntimeException`.
* The `Producer` and `Consumer` threads are considered uninterruptible.
* The `Consumer` does not implement a `take(N)` method, terminating the queue early if it has received enough items (i.e. it is assumed the `Consumer` will always consume all items).

These caveats are all to minimize the number of `catch` clauses needed to use this class under normal circumstances (e.g. to prevent the need to catch `InterruptedException` or `ExecutionException`).

## Usage example

This example sets up a bounded queue of size `5` items, and submits the integers `0` to `19` inclusive to the queue from the `Producer`.
These are then printed out by the `Consumer`.

```java
try (Yielder<Integer> yielder = new Yielder<>(5, receiver -> {
    for (int i = 0; i < 20; i++) {
        System.out.println("Producing " + i);
        receiver.yield(i);
    }
    System.out.println("Producer exiting");
}, item -> {
    System.out.println("  Consuming " + item);
    try {
        Thread.sleep(200);
    } catch (InterruptedException e) {
        throw new RuntimeException(e);
    }
})) {
    // Block and wait for producer and consumer exit
}
System.out.println("Finished");
```

or, using traditional anonymous inner classes:

```java
try (Yielder<Integer> yielder = new Yielder<>(5, new Producer<Integer>() {
    @Override
    public void produce(Receiver<Integer> receiver) {
        for (int i = 0; i < 20; i++) {
            System.out.println("Producing " + i);
            receiver.yield(i);
        }
        System.out.println("Producer exiting");
    }
}, new Consumer<Integer>() {
    @Override
    public void consume(Integer item) {
        System.out.println("  Consuming " + item);
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
})) {
    // Block and wait for producer and consumer exit
}
System.out.println("Finished");
```

This produces the output:

```
Producing 0
Producing 1
Producing 2
Producing 3
Producing 4
  Consuming 0
Producing 5
Producing 6
  Consuming 1
Producing 7
  Consuming 2
Producing 8
  Consuming 3
Producing 9
  Consuming 4
Producing 10
  Consuming 5
Producing 11
  Consuming 6
Producing 12
  Consuming 7
Producing 13
  Consuming 8
Producing 14
  Consuming 9
Producing 15
  Consuming 10
Producing 16
  Consuming 11
Producing 17
  Consuming 12
Producing 18
  Consuming 13
Producing 19
  Consuming 14
Producer exiting
  Consuming 15
  Consuming 16
  Consuming 17
  Consuming 18
  Consuming 19
Finished
```
