# Yielder.java

A simple producer / consumer class for Java. Launches the producer in a separate thread, which provides support for the `yield` / generator pattern. Provides a bounded queue between the producer and consumer, which allows for buffering and flow control, and allowing for parallel pipelining between producer and consumer (so that the consumer can be working on consuming the previous item while the producer is working on producing the next item).

**See also: [`PipelinedOutputStream`](https://github.com/lukehutch/PipelinedOutputStream)**

## Example usage

This example sets up a bounded queue of size `5`, and submits the integers `0` to `19` inclusive to the queue from the producer (launched in a new thread). These are then printed out by the consumer (the main thread).

Since the queue size is smaller than the number of submitted items, the producer will block once the queue is full. The consumer will block on `hasNext()` when the queue is empty, as long as the producer is still running.

The fundamental pattern is:

```java
Iterable<T> iterable = new Yielder<T>(queueSize) {
    @Override
    public void produce() {
        T someT = makeNewT();
        yield(someT);
    }
};
```

`Yielder<T>` implements `Iterable<T>`, so the consumer can use that to iterate through the result. For example:

```java
for (Integer item : new Yielder<Integer>(/* queueSize = */ 5) {
    @Override
    public void produce() {
        for (int i = 0; i < 20; i++) {
            System.out.println("Producing " + i);
            yield(i);
        }
        System.out.println("Producer exiting");
    }
}) {
    System.out.println("  Consuming " + item);
    Thread.sleep(200);
}
System.out.println("Finished");
```

### Output

The above example produces the following output (modulo nondeterminism). Note how the first five items are produced, filling the queue, before the consumer has a chance to start iterating through the queue items (although if the consumer thread (the main thread) had started iterating more quickly, the consumer could have started consuming even after the first item had been produced). The producer subsequently blocks, waiting for an empty slot in the queue before it can produce another item.

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

## Caveats

* `Yielder<T>` implements `Iterable<T>`, in other words `Yielder#iterator()` returns an `Iterator<T>` with methods `boolean hasNext()` and `T next()`. However these methods have semantics that are unusual compared to most Java iterators:
  * `hasNext()` may block -- the producer will block when calling `yield()` if the queue is full, whereas the consumer will block on `hasNext()` if the queue is empty.
  * If the producer thread throws an uncaught exception, it will be re-thrown to the consumer wrapped in a `RuntimeException` when the consumer calls `hasNext()` or `next()`.
* The consumer (the caller) should consume all items in the `Iterable<T>`, so that `hasNext()` returns `false`, in order to verify the producer thread has produced all items and shut down. Alternatively, you can shut down the producer early (before consuming all items) by calling `Yielder#shutdownProducerThread()`, which will also attempt to interrupt the producer thread, and then clear the queue.
* If you do call `Yielder#shutdownProducerThread()` from the consumer, then when the blocking call to `yield(T)` is interrupted, the `InterruptedException` is thrown from `yield(T)` wrapped in a `RuntimeException`. This is so that `yield(T)` does not have to declare `throws InterruptedException`, which would create a lot of extra boilerplate in the most common usecase, where the consumer never tries to interrupt the producer. This `RuntimeException` is caught by the caller of the `produce()` method, and triggers the shutdown of the producer thread, and the clearing of any un-consumed items in the queue. To properly handle interruption (e.g. if you need to clean up resources), use `try...finally` around the `yield(T)` call, or catch the wrapped exception directly:

```java
Iterable<T> iterable = new Yielder<T>(queueSize) {
    @Override
    public void produce() {
        T someT = makeNewT();
        try {
            yield(someT);
        } catch (RuntimeException e) {
            if (e instanceof InterruptedException) {
                // yield call was interrupted by consumer
            }
            throw e;
        }
    }
};
```
