/*
 * Yielder.java
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/Yielder
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple producer / consumer class for Java. Launches the producer in a separate thread, which provides support
 * for the yield / generator pattern. Provides a bounded queue between the producer and consumer, which allows for
 * buffering and flow control, and allowing for parallel pipelining between producer and consumer (so that the
 * consumer can be working on consuming the previous item while the producer is working on producing the next item).
 * 
 * @author Luke Hutchison
 */
public abstract class Yielder<T> implements Iterable<T> {
    /** The queue. */
    private ArrayBlockingQueue<Optional<T>> boundedQueue;

    /** An executor service for the producer and consumer threads. */
    private ExecutorService executor;

    /** The {@link Future} used to await termination of the producer thread. */
    private Future<Void> producerThreadFuture;

    /** Used to generate unique thread names. */
    private static final AtomicInteger threadIndex = new AtomicInteger();

    /** True when {@link close()} has been called. */
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    /** Construct a {@link Yielder} with a bounded queue of the specified length, and launch the producer thread. */
    public Yielder(int queueSize) {
        // Set up the bounded queue
        boundedQueue = new ArrayBlockingQueue<Optional<T>>(queueSize);
        // Start the producer thread
        startProducerThread();
    }

    /** Override this method with the producer code. */
    protected abstract void produce() throws Exception;

    /** Yield an item (called by a {@link ProducerLambda}). */
    public final void yield(T item) {
        try {
            this.boundedQueue.put(Optional.of(item));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /** Start the producer thread. */
    private void startProducerThread() {
        // Create thread executor
        executor = Executors.newFixedThreadPool(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                final Thread thread = new Thread(r, "Producer-" + threadIndex.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });

        // Launch producer thread
        producerThreadFuture = executor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                try {
                    // Execute producer method
                    produce();
                } finally {
                    // Send end of queue marker to consumer
                    boundedQueue.put(Optional.empty());
                    // Cannot call shutdownProducerThread() here, since
                    // we're running in the producer thread
                }
                return null;
            }
        });
    }

    /**
     * Shut down the producer thread. This is called automatically when the consumer's {@link Iterator#hasNext()}
     * returns false, i.e. when the producer has produced the last item and the consumer has consumed it.
     */
    public void shutdownProducerThread() {
        if (!isShutdown.getAndSet(true)) {
            Throwable producerException = null;
            if (!producerThreadFuture.isDone()) {
                // Cancel producer if it's still running
                producerThreadFuture.cancel(true);
            }
            try {
                // Block on producer thread completion
                producerThreadFuture.get();
            } catch (CancellationException | InterruptedException e) {
                // Ignore
            } catch (ExecutionException e) {
                producerException = e.getCause();
            }
            // Shut down executor service
            try {
                executor.shutdown();
            } catch (final SecurityException e) {
                // Ignore
            }
            boolean terminated = false;
            try {
                // Await termination
                terminated = executor.awaitTermination(5000, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException e) {
                // Ignore
            }
            if (!terminated) {
                try {
                    executor.shutdownNow();
                } catch (final SecurityException e) {
                    throw new RuntimeException(e);
                }
            }
            executor = null;
            if (producerException != null) {
                RuntimeException e = new RuntimeException("Exception in producer");
                if (producerException != null) {
                    e.addSuppressed(producerException);
                }
                throw e;
            }
        }
    }

    /** Return an {@link Iterator} for the items produced by the producer. */
    @Override
    public Iterator<T> iterator() {
        var yielder = this;
        return new Iterator<T>() {
            private Optional<T> next;

            private Optional<T> getNext() {
                if (next == null) {
                    try {
                        next = boundedQueue.take();
                    } catch (InterruptedException e) {
                        // Cancel the producer thread if the consumer is interrupted
                        yielder.shutdownProducerThread();
                        throw new RuntimeException(e);
                    }
                }
                return next;
            }

            private Optional<T> takeNext() {
                Optional<T> _next = getNext();
                next = null;
                return _next;
            }

            @Override
            public boolean hasNext() {
                boolean empty = getNext().isEmpty();
                if (empty) {
                    // Shut down thread pool once there are no more items
                    yielder.shutdownProducerThread();
                }
                return !empty;
            }

            @Override
            public T next() {
                Optional<T> _next = takeNext();
                if (_next.isEmpty()) {
                    yielder.shutdownProducerThread();
                    throw new IllegalArgumentException("No next item");
                }
                return _next.get();
            }
        };
    }
}
