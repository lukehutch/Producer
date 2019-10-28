# Yielder.java

A simple producer / consumer class for Java. Launches the producer in a separate thread, which provides support for the `yield` / generator pattern. Provides a bounded queue between the producer and consumer, which allows for buffering and flow control.

## Caveats

* `Yielder<T>` implements `Iterable<T>`, but note both its `hasNext()` and `next()` methods are blocking calls.
* The consumer (the caller) should consume all items in the `Iterable<T>`, so that `hasNext()` returns false, in order to shut down the producer thread. Alternatively, you can shut down the producer early (before consuming all items) by calling `Yielder#shutdownProducerThread()`, which will attempt to interrupt the producer thread.

## Usage example

This example sets up a bounded queue of size `5`, and submits the integers `0` to `19` inclusive to the queue from the `Producer`.
These are then printed out by the `Consumer`.

### Inner class syntax

You can pass a `Producer<T>` to the `Yielder` constructor, and implement the abstract `produce()` method, calling `Producer#yield(T)` for each produced item. `Yielder<T>` implements `Iterable<T>`, so the consumer can use that to iterate through the result.

```java
for (Integer i : new Yielder<Integer>(/* queueSize = */ 5, new Producer<Integer>() {
    @Override
    public void produce() {
        for (int i = 0; i < 20; i++) {
            System.out.println("Producing " + i);
            yield(i);
        }
        System.out.println("Producer exiting");
    }
} )) {
    System.out.println("  Consuming " + i);
    Thread.sleep(200);
}
System.out.println("Finished");
```

### Lambda syntax

Alternatively, you can use lambda notation as follows. Note the use of double-brace initializer syntax `new Yielder<T>(N) {{ ... }}` wrapping a call to `Yielder#produce(() -> {})`. The call to `yield(T)` is now actually a call to `Yielder#yield(T)`, rather than `Producer#yield(T)` in the example above, which allows you to use a `FunctionalInterface` for the producer. The syntax is more unusual in this case, but there is less boilerplate than the above example -- pick whichever form you prefer.

```java
for (Integer i : new Yielder<Integer>(/* queueSize = */ 5) {{
    produce(() -> {
        for (int i = 0; i < 20; i++) {
            System.out.println("Producing " + i);
            yield(i);
        }
    });
}} ) {
    System.out.println("  Consuming " + i);
    Thread.sleep(200);
}
System.out.println("Finished");
```

### Output

The above examples both produce approximately the following output (modulo nondeterminism):

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
