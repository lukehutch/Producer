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
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
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
public class Yielder<T> implements AutoCloseable {
    /** An executor service for the producer and consumer threads. */
    private ExecutorService executor;

    /** The {@link Future} used to await termination of the producer thread. */
    private Future<Void> producerThreadFuture;

    /** The {@link Future} used to await termination of the consumer thread. */
    private Future<Void> consumerThreadFuture;

    /** Used to generate unique thread names. */
    private static final AtomicInteger threadIndex = new AtomicInteger();

    /** True when {@link close()} has been called. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Receives objects from a {@link Producer} and places them in the bounded FIFO queue to send them to the
     * {@link Consumer}.
     */
    public static final class Receiver<T> {
        private ArrayBlockingQueue<Optional<T>> queue;

        private Receiver(ArrayBlockingQueue<Optional<T>> boundedQueue) {
            this.queue = boundedQueue;
        }

        /**
         * Call from the {@link Producer} to yield an item to the {@link Consumer}. Will block if the queue is full.
         */
        public void yield(T item) {
            try {
                queue.put(Optional.of(item));
            } catch (InterruptedException e) {
                // Re-throw as a RuntimeException so that caller doesn't have to put try-catch around yield() calls.
                throw new RuntimeException(e);
            }
        }
    }

    /** Producer. */
    public interface Producer<T> {
        /** Producer method. Call {@link Receiver#yield(Object)} to send a produced item to the {@link Consumer}. */
        public void produce(Receiver<T> receiver);
    }

    /** Producer. */
    public interface Consumer<T> {
        /** Consumer method. */
        public void consume(T item);
    }

    /**
     * Construct a producer-consumer pipeline. Assign in a try-with-resources block. When the
     * {@link Yielder#close()} method is called, the calling thread will block until the {@link Producer} and
     * {@link Consumer} threads have terminated.
     */
    public Yielder(int queueSize, Producer<T> producer, Consumer<T> consumer) {
        var boundedQueue = new ArrayBlockingQueue<Optional<T>>(queueSize);
        executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                final Thread thread = new Thread(r,
                        Yielder.class.getSimpleName() + "-" + threadIndex.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });

        // Launch consumer thread
        consumerThreadFuture = executor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                while (true) {
                    Optional<T> head = boundedQueue.take();
                    if (head.isEmpty()) {
                        // Got end of queue marker
                        break;
                    }
                    // Consume item
                    consumer.consume(head.get());
                }
                return null;
            }
        });

        // Launch producer thread
        producerThreadFuture = executor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                try {
                    // Launch producer
                    producer.produce(new Receiver<T>(boundedQueue));
                } finally {
                    // Send end of queue marker to consumer
                    boundedQueue.put(Optional.empty());
                }
                return null;
            }
        });

    }

    /** Close. Blocks on producer and consumer completing their work. */
    @Override
    public void close() {
        if (!closed.getAndSet(true)) {
            Exception producerException = null;
            try {
                // Block on producer thread completion
                producerThreadFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                producerException = e;
            }
            Exception consumerException = null;
            try {
                // Send poison pill to consumer
                // Block on consumer thread completion
                consumerThreadFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                consumerException = e;
            }
            // Try to shut down executor service cleanly
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
            if (producerException != null || consumerException != null) {
                RuntimeException e = new RuntimeException("Exception in " + Yielder.class.getSimpleName());
                if (producerException != null) {
                    e.addSuppressed(producerException);
                }
                if (consumerException != null) {
                    e.addSuppressed(consumerException);
                }
                throw e;
            }
        }
    }
}
