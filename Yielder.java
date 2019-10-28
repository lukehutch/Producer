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
import java.io.OutputStream;
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
 * Buffer an {@link OutputStream} in a separate process, to separate data generation from data compression or
 * writing to disk.
 */
public class Yielder<T> implements Iterable<T> {
    /** The queue. */
    private ArrayBlockingQueue<Optional<T>> boundedQueue;

    /** An executor service for the producer and consumer threads. */
    private ExecutorService executor;

    /** The {@link Producer}. */
    private Producer<T> producer;

    /** The {@link Future} used to await termination of the producer thread. */
    private Future<Void> producerThreadFuture;

    /** Used to generate unique thread names. */
    private static final AtomicInteger threadIndex = new AtomicInteger();

    /** True when {@link close()} has been called. */
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    /** Producer. */
    public static abstract class Producer<T> {
        private ArrayBlockingQueue<Optional<T>> queue;

        private void setQueue(ArrayBlockingQueue<Optional<T>> boundedQueue) {
            this.queue = boundedQueue;
        }

        public final void yield(T item) {
            try {
                this.queue.put(Optional.of(item));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        /** Producer method. Call {@link #yield(Object)} to send a produced item to the consumer. */
        public abstract void produce() throws Exception;
    }

    /**
     * {@link FunctionalInterface} for use with {@link Yielder} instead of a {@link Producer} (which can only be
     * specified using anonymous inner class or subclass syntax).
     */
    @FunctionalInterface
    public static interface ProducerLambda<T> {
        public void produce() throws Exception;
    }

    /** Set up a {@link Yielder} ready to accept a {@link ProducerLambda} via a call to {@link #produce()}. */
    public Yielder(int queueSize) {
        boundedQueue = new ArrayBlockingQueue<Optional<T>>(queueSize);
    }

    /** Launch a {@link Producer} thread. */
    public Yielder(int queueSize, Producer<T> producer) {
        this(queueSize);

        // Launch producer thread
        if (producer == null) {
            throw new IllegalArgumentException("producer is null");
        }
        this.producer = producer;
        launchProducerThread();
    }

    /** Configure and launch a {@link Producer} thread. */
    protected final void produce(ProducerLambda<T> producerLambda) {
        if (producer != null) {
            throw new IllegalArgumentException("Cannot call produce() twice");
        }
        producer = new Producer<T>() {
            @Override
            public void produce() throws Exception {
                producerLambda.produce();
            }
        };
        launchProducerThread();
    }

    /** Yield an item (called by a {@link ProducerLambda}). */
    public void yield(T item) {
        if (producer == null) {
            throw new IllegalArgumentException("Must call produce() first");
        }
        producer.yield(item);
    }

    /** Launch the {@link Producer} thread. */
    private void launchProducerThread() {
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
        producer.setQueue(boundedQueue);
        producerThreadFuture = executor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                try {
                    // Launch producer
                    producer.produce();
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
     * Shut down the producer thread. This is called automatically when {@link Iterator#hasNext()} returns false,
     * i.e. when the producer has produced the last item and the consumer has consumed it.
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
                RuntimeException e = new RuntimeException("Exception in Producer");
                if (producerException != null) {
                    e.addSuppressed(producerException);
                }
                throw e;
            }
        }
    }

    /** Return an {@link Iterator} for the items produced by the {@link Producer}. */
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
                        producerThreadFuture.cancel(true);
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
                    throw new IllegalArgumentException("No next item");
                }
                return _next.get();
            }
        };
    }
}
