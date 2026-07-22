/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cluster.routing.allocation;

import org.elasticsearch.cluster.ClusterInfo;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.telemetry.metric.DoubleWithAttributes;
import org.elasticsearch.telemetry.metric.MeterRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes per-node cache commitment metrics derived from {@link ClusterInfo#getNodeCacheSizeAndCommitments()}.
 * Each metric is expressed as a fraction of the node's total cache size: 1.0 means exactly committed, 1.5 means
 * 50% over-committed.
 * <p>
 * Two gauges are emitted per node for which data is available:
 * <ul>
 *     <li>boosted commitment only</li>
 *     <li>total commitment (boosted + unboosted)</li>
 * </ul>
 */
public class NodeCacheCommitmentMetrics {

    public static final String BOOSTED_CACHE_COMMITMENT_METRIC_NAME = "es.allocator.cache_commitments.boosted.current";
    public static final String TOTAL_CACHE_COMMITMENT_METRIC_NAME = "es.allocator.cache_commitments.total.current";

    private final ClusterService clusterService;
    private final AtomicReference<List<DoubleWithAttributes>> lastBoostedCommitmentMetrics = new AtomicReference<>(List.of());
    private final AtomicReference<List<DoubleWithAttributes>> lastTotalCommitmentMetrics = new AtomicReference<>(List.of());
    private volatile boolean lastMetricsCollected = true;

    public NodeCacheCommitmentMetrics(MeterRegistry meterRegistry, ClusterService clusterService) {
        this.clusterService = clusterService;
        meterRegistry.registerDoublesGauge(
            BOOSTED_CACHE_COMMITMENT_METRIC_NAME,
            "Boosted cache commitment as a fraction of total cache size per node",
            "1",
            this::getBoostedCommitmentMetrics
        );
        meterRegistry.registerDoublesGauge(
            TOTAL_CACHE_COMMITMENT_METRIC_NAME,
            "Total cache commitment (boosted + unboosted) as a fraction of total cache size per node",
            "1",
            this::getTotalCommitmentMetrics
        );
    }

    public void onNewInfo(ClusterInfo clusterInfo) {
        if (clusterService.lifecycleState() != Lifecycle.State.STARTED || lastMetricsCollected == false) {
            return;
        }

        var nodeCacheSizeAndCommitments = clusterInfo.getNodeCacheSizeAndCommitments();
        if (nodeCacheSizeAndCommitments.isEmpty()) {
            return;
        }

        var clusterState = clusterService.state();
        var boostedMetrics = new ArrayList<DoubleWithAttributes>(nodeCacheSizeAndCommitments.size());
        var totalMetrics = new ArrayList<DoubleWithAttributes>(nodeCacheSizeAndCommitments.size());

        for (var entry : nodeCacheSizeAndCommitments.entrySet()) {
            var commitments = entry.getValue();
            if (commitments.cacheSizeInBytes() <= 0) {
                continue;
            }

            var node = clusterState.nodes().get(entry.getKey());
            if (node == null) {
                continue;
            }

            var attrs = getAttributesForNode(node);
            double cacheSize = commitments.cacheSizeInBytes();
            boostedMetrics.add(new DoubleWithAttributes(commitments.boostedCacheCommitmentInBytes() / cacheSize, attrs));
            totalMetrics.add(
                new DoubleWithAttributes(
                    (commitments.boostedCacheCommitmentInBytes() + commitments.unboostedCacheCommitmentInBytes()) / cacheSize,
                    attrs
                )
            );
        }

        lastMetricsCollected = false;
        lastBoostedCommitmentMetrics.set(boostedMetrics);
        lastTotalCommitmentMetrics.set(totalMetrics);
    }

    private static Map<String, Object> getAttributesForNode(DiscoveryNode node) {
        return Map.of("es_node_id", node.getId(), "es_node_name", node.getName());
    }

    // visible for testing
    final Collection<DoubleWithAttributes> getBoostedCommitmentMetrics() {
        var metrics = lastBoostedCommitmentMetrics.getAndSet(List.of());
        lastMetricsCollected = true;
        return metrics;
    }

    // visible for testing
    final Collection<DoubleWithAttributes> getTotalCommitmentMetrics() {
        var metrics = lastTotalCommitmentMetrics.getAndSet(List.of());
        lastMetricsCollected = true;
        return metrics;
    }
}
