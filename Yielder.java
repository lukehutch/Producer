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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    private final ArrayBlockingQueue<Optional<T>> boundedQueue;

    /** The iterator. */
    private final Iterator<T> iterator;

    /** An executor service for the producer and consumer threads. */
    private final ExecutorService producerThreadExecutor;

    /** The {@link Future} used to await termination of the producer thread. */
    private final Future<Void> producerThreadFuture;

    /** True when the producer thread has been shut down. */
    private final AtomicBoolean producerIsShutdown = new AtomicBoolean(false);

    /** Non-null when the producer has thrown a non-caught exception. */
    private AtomicReference<Throwable> producerException = new AtomicReference<>();

    /** Create named daemon threads */
    private static class DaemonThreadFactory implements ThreadFactory {
        /** The name prefix. */
        private String namePrefix;

        /** Used to generate unique thread names. */
        private static final AtomicInteger threadIndex = new AtomicInteger();

        /** Constructor */
        public DaemonThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            final Thread thread = new Thread(r, namePrefix + "-" + threadIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    /** Construct a {@link Yielder} with a bounded queue of the specified length, and launch the producer thread. */
    public Yielder(int queueSize) {
        // Set up the bounded queue
        boundedQueue = new ArrayBlockingQueue<Optional<T>>(queueSize);

        // Create thread executor
        producerThreadExecutor = Executors.newFixedThreadPool(1, new DaemonThreadFactory("Producer"));

        // Launch producer thread
        producerThreadFuture = producerThreadExecutor.submit(() -> {
            boolean normalTermination = false;
            try {
                try {
                    // Execute producer method
                    produce();
                    // On normal termination, send end-of-queue marker
                    boundedQueue.put(Optional.empty());
                    // After end-of-queue marker has been successfully written without interruption,
                    // record normal termination
                    normalTermination = true;
                } catch (InterruptedException e1) {
                    // Ignore
                } catch (RuntimeException e2) {
                    if (e2.getCause() instanceof InterruptedException) {
                        // Got InterruptedException wrapped in RuntimeException (from yield()), ignore
                    } else {
                        // For any other RuntimeException, set producer exception
                        producerException.set(e2);
                    }
                } catch (Exception e3) {
                    // For any other exception, set producer exception
                    producerException.set(e3);
                }
            } finally {
                // Shut down producer, if it is not already shut down
                if (!producerIsShutdown.get()) {
                    // If producer exited early due to interruption or throwing an exception,
                    // then clear the queue in shutdownProducerThread().
                    boolean clearQueue = !normalTermination;
                    shutdownProducerThread(clearQueue);
                }
            }
            // Return null so that Callable lambda is submitted, rather than Runnable
            return null;
        });

        // Create the iterator for the consumer
        iterator = new Iterator<T>() {
            /** The next item in the queue (empty, if at end of queue). */
            private Optional<T> next;

            private Optional<T> getNext() {
                if (next == null) {
                    try {
                        next = boundedQueue.take();
                    } catch (InterruptedException e) {
                        // If the consumer is interrupted, cancel the producer thread
                        cancelProducerThread();
                        // Re-set the interrupt status of the current thread
                        Thread.currentThread().interrupt();
                        // Re-throw as RuntimeException, since the iterator sequence
                        // will end earlier than expected, and the receiver should not 
                        // assume the returned sequence is the full sequence
                        throw new RuntimeException(e);
                    }
                }
                // If producer threw an exception, re-throw it in the consumer
                if (producerException.get() != null) {
                    throw new RuntimeException("Producer threw an exception", producerException.get());
                }
                return next;
            }

            private Optional<T> takeNext() {
                Optional<T> _next = getNext();
                if (!next.isEmpty()) {
                    // Once end-of-queue is reached, don't consume item,
                    // so that hasNext() will continue to return false
                    next = null;
                }
                return _next;
            }

            @Override
            public boolean hasNext() {
                return !getNext().isEmpty();
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

    /** Cancel the producer thread. */
    private void cancelProducerThread() {
        // Cancel producer if it's still running. This will interrupt attempts to put new values in the queue.
        if (!producerThreadFuture.isDone()) {
            producerThreadFuture.cancel(true);
        }
    }

    /**
     * Shut down the producer thread and clear the queue. The only time clearQueue should be false is if the
     * producer terminates normally, and has written its own end-of-queue marker. For all other situations (where
     * termination is abnormal, due to exception, or early, due to the consumer calling
     * {@link #shutdownProducerThread()}), the queue should be cleared so that the end-of-queue marker can be
     * written without blocking.
     */
    private void shutdownProducerThread(boolean clearQueue) {
        if (!producerIsShutdown.getAndSet(true)) {
            // Shut down producer in a new thread, so that yielders can be chained together and all shut their
            // upstream producer down without any danger that killing an upstream thread will leave the thread
            // pool in an idle and un-shutdown state.
            new DaemonThreadFactory("Producer-Shutdown").newThread(() -> {
                cancelProducerThread();
                try {
                    // Block on producer thread completion
                    producerThreadFuture.get();
                } catch (CancellationException | InterruptedException e) {
                    // Ignore
                } catch (ExecutionException e) {
                    producerException.set(e.getCause());
                }
                if (clearQueue) {
                    // Clear the queue after the producer thread has shut down
                    boundedQueue.clear();
                    // Then push the end-of-queue marker so that the consumer always terminates
                    // (if clearQueue is false, the end-of-queue marker has already been pushed by the producer
                    // thread without clearing the queue, which is important since it is only the producer thread
                    // that should block on a full queue) 
                    try {
                        boundedQueue.put(Optional.empty());
                    } catch (InterruptedException e1) {
                        // Should not happen
                        throw new RuntimeException("Could not push end-of-queue marker");
                    }
                }
                // Shut down executor service
                try {
                    producerThreadExecutor.shutdown();
                } catch (final SecurityException e) {
                    // Ignore
                }
                boolean terminated = false;
                try {
                    // Await termination
                    terminated = producerThreadExecutor.awaitTermination(5000, TimeUnit.MILLISECONDS);
                } catch (final InterruptedException e) {
                    // Ignore
                }
                if (!terminated) {
                    try {
                        producerThreadExecutor.shutdownNow();
                    } catch (final SecurityException e) {
                        throw new RuntimeException(e);
                    }
                }
            }).start();
        }
    }

    /**
     * Initiate immediate termination -- interrupt and shut down the producer thread, and clear the queue.
     */
    public void shutdownProducerThread() {
        shutdownProducerThread(/* clearQueue = */ true);
    }

    /** Return an {@link Iterator} for the items produced by the producer. */
    @Override
    public Iterator<T> iterator() {
        return iterator;
    }

    /**
     * Yield an item to the consumer.
     * 
     * @throws RuntimeException If the producer thread is interrupted (by calling {@link #shutdownProducerThread()},
     *                          this method will throw {@link RuntimeException} with the
     *                          {@link InterruptedException} as the cause.
     */
    public final void yield(T item) {
        if (producerIsShutdown.get()) {
            // If producer is already shut down, simulate boundedQueue.put() getting interrupted below
            throw new RuntimeException(new InterruptedException());
        }
        try {
            this.boundedQueue.put(Optional.of(item));
        } catch (InterruptedException e) {
            // Interrupted by consumer calling shutdownProducerThread() -- need to wrap the InterruptedException
            // in a RuntimeException, so that yield() does not need to declare "throws InterruptedException"
            throw new RuntimeException(e);
        }
    }

    /** Override this method with the producer code. */
    protected abstract void produce() throws Exception;
}
