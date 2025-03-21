/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.search.aggregations.metrics;

import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.DoubleArray;
import org.elasticsearch.common.util.LongArray;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.index.fielddata.NumericDoubleValues;
import org.elasticsearch.index.fielddata.SortedNumericDoubleValues;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.search.aggregations.LeafBucketCollectorBase;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;

import java.io.IOException;
import java.util.Map;

class StatsAggregator extends NumericMetricsAggregator.MultiDoubleValue {

    final DocValueFormat format;

    LongArray counts;
    DoubleArray sums;
    DoubleArray compensations;
    DoubleArray mins;
    DoubleArray maxes;

    StatsAggregator(String name, ValuesSourceConfig config, AggregationContext context, Aggregator parent, Map<String, Object> metadata)
        throws IOException {
        super(name, config, context, parent, metadata);
        assert config.hasValues();
        counts = bigArrays().newLongArray(1, true);
        sums = bigArrays().newDoubleArray(1, true);
        compensations = bigArrays().newDoubleArray(1, true);
        mins = bigArrays().newDoubleArray(1, false);
        mins.fill(0, mins.size(), Double.POSITIVE_INFINITY);
        maxes = bigArrays().newDoubleArray(1, false);
        maxes.fill(0, maxes.size(), Double.NEGATIVE_INFINITY);
        this.format = config.format();
    }

    @Override
    public LeafBucketCollector getLeafCollector(SortedNumericDoubleValues values, LeafBucketCollector sub) {
        final CompensatedSum kahanSummation = new CompensatedSum(0, 0);
        return new LeafBucketCollectorBase(sub, values) {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                if (values.advanceExact(doc)) {
                    maybeGrow(bucket);
                    final int valuesCount = values.docValueCount();
                    counts.increment(bucket, valuesCount);
                    double min = mins.get(bucket);
                    double max = maxes.get(bucket);
                    // Compute the sum of double values with Kahan summation algorithm which is more
                    // accurate than naive summation.
                    kahanSummation.reset(sums.get(bucket), compensations.get(bucket));
                    for (int i = 0; i < valuesCount; i++) {
                        double value = values.nextValue();
                        kahanSummation.add(value);
                        min = Math.min(min, value);
                        max = Math.max(max, value);
                    }
                    sums.set(bucket, kahanSummation.value());
                    compensations.set(bucket, kahanSummation.delta());
                    mins.set(bucket, min);
                    maxes.set(bucket, max);
                }
            }
        };
    }

    @Override
    public LeafBucketCollector getLeafCollector(NumericDoubleValues values, LeafBucketCollector sub) {
        return new LeafBucketCollectorBase(sub, values) {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                if (values.advanceExact(doc)) {
                    maybeGrow(bucket);
                    counts.increment(bucket, 1L);
                    double value = values.doubleValue();
                    SumAggregator.computeSum(bucket, value, sums, compensations);
                    updateMinsAndMaxes(bucket, value, mins, maxes);
                }
            }
        };
    }

    static void updateMinsAndMaxes(long bucket, double value, DoubleArray mins, DoubleArray maxes) {
        double min = mins.get(bucket);
        double updated = Math.min(value, min);
        if (updated != min) {
            mins.set(bucket, updated);
        }
        double max = maxes.get(bucket);
        updated = Math.max(value, max);
        if (updated != max) {
            maxes.set(bucket, updated);
        }
    }

    private void maybeGrow(long bucket) {
        if (bucket >= counts.size()) {
            final long from = counts.size();
            final long overSize = BigArrays.overSize(bucket + 1);
            var bigArrays = bigArrays();
            counts = bigArrays.resize(counts, overSize);
            sums = bigArrays.resize(sums, overSize);
            compensations = bigArrays.resize(compensations, overSize);
            mins = bigArrays.resize(mins, overSize);
            maxes = bigArrays.resize(maxes, overSize);
            mins.fill(from, overSize, Double.POSITIVE_INFINITY);
            maxes.fill(from, overSize, Double.NEGATIVE_INFINITY);
        }
    }

    @Override
    public boolean hasMetric(String name) {
        return InternalStats.Metrics.hasMetric(name);
    }

    @Override
    public double metric(String name, long owningBucketOrd) {
        if (owningBucketOrd >= counts.size()) {
            return switch (InternalStats.Metrics.resolve(name)) {
                case count, sum -> 0;
                case min -> Double.POSITIVE_INFINITY;
                case max -> Double.NEGATIVE_INFINITY;
                case avg -> Double.NaN;
            };
        }
        return switch (InternalStats.Metrics.resolve(name)) {
            case count -> counts.get(owningBucketOrd);
            case sum -> sums.get(owningBucketOrd);
            case min -> mins.get(owningBucketOrd);
            case max -> maxes.get(owningBucketOrd);
            case avg -> sums.get(owningBucketOrd) / counts.get(owningBucketOrd);
        };
    }

    @Override
    public InternalAggregation buildAggregation(long bucket) {
        if (bucket >= sums.size()) {
            return buildEmptyAggregation();
        }
        return new InternalStats(name, counts.get(bucket), sums.get(bucket), mins.get(bucket), maxes.get(bucket), format, metadata());
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return InternalStats.empty(name, format, metadata());
    }

    @Override
    public void doClose() {
        Releasables.close(counts, maxes, mins, sums, compensations);
    }
}
