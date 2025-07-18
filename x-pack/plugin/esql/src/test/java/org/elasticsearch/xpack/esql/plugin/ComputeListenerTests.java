/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plugin;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRunnable;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.compute.operator.DriverCompletionInfo;
import org.elasticsearch.compute.operator.DriverProfile;
import org.elasticsearch.compute.operator.DriverSleeps;
import org.elasticsearch.compute.operator.PlanProfile;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.tasks.TaskCancelledException;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThan;

public class ComputeListenerTests extends ESTestCase {
    private ThreadPool threadPool;

    @Before
    public void setUpTransportService() {
        threadPool = new TestThreadPool(getTestName());
    }

    @After
    public void shutdownTransportService() {
        terminate(threadPool);
    }

    private DriverCompletionInfo randomCompletionInfo() {
        return new DriverCompletionInfo(
            randomNonNegativeLong(),
            randomNonNegativeLong(),
            randomList(
                0,
                2,
                () -> new DriverProfile(
                    randomIdentifier(),
                    randomIdentifier(),
                    randomIdentifier(),
                    randomNonNegativeLong(),
                    randomNonNegativeLong(),
                    randomNonNegativeLong(),
                    randomNonNegativeLong(),
                    randomNonNegativeLong(),
                    List.of(),
                    DriverSleeps.empty()
                )
            ),
            randomList(
                0,
                2,
                () -> new PlanProfile(randomIdentifier(), randomIdentifier(), randomIdentifier(), randomAlphaOfLengthBetween(1, 1024))
            )
        );
    }

    public void testEmpty() {
        PlainActionFuture<DriverCompletionInfo> results = new PlainActionFuture<>();
        try (var ignored = new ComputeListener(threadPool, () -> {}, results)) {
            assertFalse(results.isDone());
        }
        assertTrue(results.isDone());
        assertThat(results.actionGet(10, TimeUnit.SECONDS).driverProfiles(), empty());
    }

    public void testCollectComputeResults() {
        PlainActionFuture<DriverCompletionInfo> future = new PlainActionFuture<>();
        long documentsFound = 0;
        long valuesLoaded = 0;
        List<DriverProfile> allProfiles = new ArrayList<>();
        AtomicInteger onFailure = new AtomicInteger();
        try (var computeListener = new ComputeListener(threadPool, onFailure::incrementAndGet, future)) {
            int tasks = randomIntBetween(1, 100);
            for (int t = 0; t < tasks; t++) {
                if (randomBoolean()) {
                    ActionListener<Void> subListener = computeListener.acquireAvoid();
                    threadPool.schedule(
                        ActionRunnable.wrap(subListener, l -> l.onResponse(null)),
                        TimeValue.timeValueNanos(between(0, 100)),
                        threadPool.generic()
                    );
                } else {
                    var info = randomCompletionInfo();
                    documentsFound += info.documentsFound();
                    valuesLoaded += info.valuesLoaded();
                    allProfiles.addAll(info.driverProfiles());
                    ActionListener<DriverCompletionInfo> subListener = computeListener.acquireCompute();
                    threadPool.schedule(
                        ActionRunnable.wrap(subListener, l -> l.onResponse(info)),
                        TimeValue.timeValueNanos(between(0, 100)),
                        threadPool.generic()
                    );
                }
            }
        }
        DriverCompletionInfo actual = future.actionGet(10, TimeUnit.SECONDS);
        assertThat(actual.documentsFound(), equalTo(documentsFound));
        assertThat(actual.valuesLoaded(), equalTo(valuesLoaded));
        assertThat(
            actual.driverProfiles().stream().collect(Collectors.toMap(p -> p, p -> 1, Integer::sum)),
            equalTo(allProfiles.stream().collect(Collectors.toMap(p -> p, p -> 1, Integer::sum)))
        );
        assertThat(onFailure.get(), equalTo(0));
    }

    public void testCancelOnFailure() {
        Queue<Exception> rootCauseExceptions = ConcurrentCollections.newQueue();
        IntStream.range(0, between(1, 100))
            .forEach(
                n -> rootCauseExceptions.add(new CircuitBreakingException("breaking exception " + n, CircuitBreaker.Durability.TRANSIENT))
            );
        int successTasks = between(1, 50);
        int failedTasks = between(1, 100);
        PlainActionFuture<DriverCompletionInfo> rootListener = new PlainActionFuture<>();
        final AtomicInteger onFailure = new AtomicInteger();
        try (var computeListener = new ComputeListener(threadPool, onFailure::incrementAndGet, rootListener)) {
            for (int i = 0; i < successTasks; i++) {
                ActionListener<DriverCompletionInfo> subListener = computeListener.acquireCompute();
                threadPool.schedule(
                    ActionRunnable.wrap(subListener, l -> l.onResponse(randomCompletionInfo())),
                    TimeValue.timeValueNanos(between(0, 100)),
                    threadPool.generic()
                );
            }
            for (int i = 0; i < failedTasks; i++) {
                ActionListener<?> subListener = randomBoolean() ? computeListener.acquireAvoid() : computeListener.acquireCompute();
                threadPool.schedule(ActionRunnable.wrap(subListener, l -> {
                    Exception ex = rootCauseExceptions.poll();
                    if (ex == null) {
                        ex = new TaskCancelledException("task was cancelled");
                    }
                    l.onFailure(ex);
                }), TimeValue.timeValueNanos(between(0, 100)), threadPool.generic());
            }
        }
        ExecutionException failure = expectThrows(ExecutionException.class, () -> rootListener.get(10, TimeUnit.SECONDS));
        Throwable cause = failure.getCause();
        assertNotNull(failure);
        assertThat(cause, instanceOf(CircuitBreakingException.class));
        assertThat(failure.getSuppressed().length, lessThan(10));
        assertThat(onFailure.get(), greaterThanOrEqualTo(1));
    }

    public void testCollectWarnings() throws Exception {
        AtomicLong documentsFound = new AtomicLong();
        AtomicLong valuesLoaded = new AtomicLong();
        List<DriverProfile> allProfiles = new ArrayList<>();
        Map<String, Set<String>> allWarnings = new HashMap<>();
        ActionListener<DriverCompletionInfo> rootListener = new ActionListener<>() {
            @Override
            public void onResponse(DriverCompletionInfo result) {
                assertThat(result.documentsFound(), equalTo(documentsFound.get()));
                assertThat(result.valuesLoaded(), equalTo(valuesLoaded.get()));
                assertThat(
                    result.driverProfiles().stream().collect(Collectors.toMap(p -> p, p -> 1, Integer::sum)),
                    equalTo(allProfiles.stream().collect(Collectors.toMap(p -> p, p -> 1, Integer::sum)))
                );
                Map<String, Set<String>> responseHeaders = threadPool.getThreadContext()
                    .getResponseHeaders()
                    .entrySet()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> new HashSet<>(e.getValue())));
                assertThat(responseHeaders, equalTo(allWarnings));
            }

            @Override
            public void onFailure(Exception e) {
                throw new AssertionError(e);
            }
        };
        AtomicInteger onFailure = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        try (
            var computeListener = new ComputeListener(
                threadPool,
                onFailure::incrementAndGet,
                ActionListener.runAfter(rootListener, latch::countDown)
            )
        ) {
            int tasks = randomIntBetween(1, 100);
            for (int t = 0; t < tasks; t++) {
                if (randomBoolean()) {
                    ActionListener<Void> subListener = computeListener.acquireAvoid();
                    threadPool.schedule(
                        ActionRunnable.wrap(subListener, l -> l.onResponse(null)),
                        TimeValue.timeValueNanos(between(0, 100)),
                        threadPool.generic()
                    );
                } else {
                    var resp = randomCompletionInfo();
                    documentsFound.addAndGet(resp.documentsFound());
                    valuesLoaded.addAndGet(resp.valuesLoaded());
                    allProfiles.addAll(resp.driverProfiles());
                    int numWarnings = randomIntBetween(1, 5);
                    Map<String, String> warnings = new HashMap<>();
                    for (int i = 0; i < numWarnings; i++) {
                        warnings.put("key" + between(1, 10), "value" + between(1, 10));
                    }
                    for (Map.Entry<String, String> e : warnings.entrySet()) {
                        allWarnings.computeIfAbsent(e.getKey(), v -> new HashSet<>()).add(e.getValue());
                    }
                    var subListener = computeListener.acquireCompute();
                    threadPool.schedule(ActionRunnable.wrap(subListener, l -> {
                        for (Map.Entry<String, String> e : warnings.entrySet()) {
                            threadPool.getThreadContext().addResponseHeader(e.getKey(), e.getValue());
                        }
                        l.onResponse(resp);
                    }), TimeValue.timeValueNanos(between(0, 100)), threadPool.generic());
                }
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertThat(onFailure.get(), equalTo(0));
    }
}
