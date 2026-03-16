/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.writeloadforecaster;

import org.apache.logging.log4j.Level;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.node.usage.NodeUsageStatsForThreadPoolsAction;
import org.elasticsearch.action.admin.cluster.node.usage.TransportNodeUsageStatsForThreadPoolsAction;
import org.elasticsearch.action.admin.cluster.reroute.ClusterRerouteRequest;
import org.elasticsearch.action.admin.cluster.reroute.TransportClusterRerouteAction;
import org.elasticsearch.action.admin.indices.stats.CommonStats;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsAction;
import org.elasticsearch.action.admin.indices.stats.ShardStats;
import org.elasticsearch.action.admin.indices.stats.TransportIndicesStatsAction;
import org.elasticsearch.action.support.ChannelActionListener;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.NodeUsageStatsForThreadPools;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.routing.allocation.IndexBalanceConstraintSettings;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.WriteLoadConstraintMonitor;
import org.elasticsearch.cluster.routing.allocation.WriteLoadConstraintSettings;
import org.elasticsearch.cluster.routing.allocation.allocator.BalancedShardsAllocator;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.Decision;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.shard.DocsStats;
import org.elasticsearch.index.shard.IndexingStats;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardPath;
import org.elasticsearch.index.store.StoreStats;
import org.elasticsearch.plugins.ClusterPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.MockLog;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TestTransportChannel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.equalTo;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0)
public class ClusterInfoWriteLoadForecasterIT extends ESIntegTestCase {

    private final Map<ShardId, Double> shardWriteLoad = new HashMap<>();

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(
            MockTransportService.TestPlugin.class,
            WriteLoadForecasterIT.FakeLicenseWriteLoadForecasterPlugin.class,
            RebalanceDisablerPlugin.class
        );
    }

    @TestLogging(
        value = "org.elasticsearch.cluster.routing.allocation.WriteLoadConstraintMonitor:TRACE",
        reason = "so we can see the monitor hotspot message"
    )
    public void testWriteLoadDefinedRelocationOrdering() {
        final long queueLatencyThresholdMillis = randomLongBetween(3000, 7000);
        final int utilizationThresholdPercent = randomIntBetween(80, 99);
        final Settings settings = createClusterInfoWriteLoadForecasterTestSettings(
            queueLatencyThresholdMillis,
            utilizationThresholdPercent
        );
        internalCluster().startMasterOnlyNode(settings);

        int numberOfNodes = randomIntBetween(5, 10);
        int numberOfIndices = 3 * numberOfNodes;

        List<String> nodeNames = internalCluster().startNodes(numberOfNodes, settings);

        Set<String> indexNames = new HashSet<>(numberOfIndices);
        for (int i = 0; i < numberOfIndices; i++) {
            final var indexName = randomIdentifier();
            indexNames.add(indexName);
            createIndex(indexName, 1, 0);
        }

        safeGet(
            client().execute(TransportClusterRerouteAction.TYPE, new ClusterRerouteRequest(TEST_REQUEST_TIMEOUT, TEST_REQUEST_TIMEOUT))
        );

        ClusterHealthResponse response = clusterAdmin().prepareHealth(TEST_REQUEST_TIMEOUT)
            .setWaitForNoRelocatingShards(true)
            .setWaitForNoInitializingShards(true)
            .setWaitForGreenStatus()
            .get();
        assertThat(response.isTimedOut(), equalTo(false));

        // check that all nodes have three shards
        ClusterState state = clusterService().state();
        for (RoutingNode routingNode : state.getRoutingNodes()) {
            assertThat(routingNode.numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(3));
        }

        // inrease in shard write load, decrease in disk usage
        // ensure that the max shard write load + the mininum will always be less than 0.9
        Map<String, List<IndexMetadata>> nodeIdsToIndexMetadata = new HashMap<>();
        for (String indexName : indexNames) {
            nodeIdsToIndexMetadata.computeIfAbsent(
                clusterService().state().routingTable(ProjectId.DEFAULT).index(indexName).shard(0).primaryShard().currentNodeId(),
                (String nodeId) -> new ArrayList<IndexMetadata>()
            ).add(clusterService().state().metadata().getProject().index(indexName));
        }

        Collections.shuffle(nodeNames);
        double shardWriteLoadBase = randomDoubleBetween(0.01, 0.05, true);
        long shardDiskUsage = 10 * randomLongBetween(100_000_000, 400_000_000);
        for (String nodeName : nodeNames) {
            List<ShardStats> shardStats = new ArrayList<>();
            for (IndexMetadata indexMetadata : nodeIdsToIndexMetadata.getOrDefault(getNodeId(nodeName), List.of())) {
                shardStats.add(
                    createShardStats(
                        indexMetadata,
                        0,
                        shardDiskUsage,
                        randomDoubleBetween(shardWriteLoadBase, shardWriteLoadBase + 0.05, true),
                        getNodeId(nodeName)
                    )
                );
            }
            mockShardStatsForNode(clusterService().state(), nodeName, shardStats);
            shardDiskUsage = Math.max(0, shardDiskUsage - randomLongBetween(100_000_000, 400_000_000));
            shardWriteLoadBase += randomDoubleBetween(0.01, 0.05, true);
        }

        // turn off shard balance factor (needed to spread shards evenly)
        updateClusterSettings(Settings.builder().put(settings).put(BalancedShardsAllocator.SHARD_BALANCE_FACTOR_SETTING.getKey(), 0.0f));

        safeGet(
            client().execute(TransportClusterRerouteAction.TYPE, new ClusterRerouteRequest(TEST_REQUEST_TIMEOUT, TEST_REQUEST_TIMEOUT))
        );

        response = clusterAdmin().prepareHealth(TEST_REQUEST_TIMEOUT)
            .setWaitForNoRelocatingShards(true)
            .setWaitForNoInitializingShards(true)
            .setWaitForGreenStatus()
            .get();
        assertThat(response.isTimedOut(), equalTo(false));

        // nodeNames is arranged from low to high in shard write load
        // simulate a hotspot on one of them, and see a shard move to the lowest one
        String hotNode = nodeNames.get(nodeNames.size() - 1);
        String hotNodeId = getNodeId(hotNode);
        simulateHotSpottingOnNode(hotNode, queueLatencyThresholdMillis, utilizationThresholdPercent + 1 / 100.0f);

        String coldNode = nodeNames.get(0);
        String coldNodeId = getNodeId(coldNode);
        System.out.println("");

        // check hotspot is detected
        MockLog.awaitLogger(
            ESIntegTestCase::refreshClusterInfo,
            WriteLoadConstraintMonitor.class,
            new MockLog.SeenEventExpectation(
                "hot spot detected message",
                WriteLoadConstraintMonitor.class.getCanonicalName(),
                Level.DEBUG,
                Strings.format("""
                    Nodes [%s] are hot-spotting, of * total ingest nodes. Reroute for hot-spotting has never previously been called. \
                    Previously hot-spotting nodes are [0 nodes]. The write thread pool queue latency threshold is [*] and the \
                    utilization threshold is [*]. Triggering reroute.
                    """, getNodeId(hotNode) + "/" + hotNode)
            )
        );

        safeGet(
            client().execute(TransportClusterRerouteAction.TYPE, new ClusterRerouteRequest(TEST_REQUEST_TIMEOUT, TEST_REQUEST_TIMEOUT))
        );

        response = clusterAdmin().prepareHealth(TEST_REQUEST_TIMEOUT)
            .setWaitForNoRelocatingShards(true)
            .setWaitForNoInitializingShards(true)
            .setWaitForGreenStatus()
            .get();
        assertThat(response.isTimedOut(), equalTo(false));

        awaitClusterState(clusterState -> {
            var routingNodes = clusterState.getRoutingNodes();
            int hotCount = routingNodes.node(hotNodeId).numberOfShardsWithState(ShardRoutingState.STARTED);
            int coldCount = routingNodes.node(coldNodeId).numberOfShardsWithState(ShardRoutingState.STARTED);
            return hotCount < 3 && coldCount > 3;
        });
    }

    public static ShardStats createShardStats(
        IndexMetadata indexMeta,
        int shardIndex,
        long shardDiskSize,
        double shardWriteLoad,
        String assignedShardNodeId
    ) {
        ShardId shardId = new ShardId(indexMeta.getIndex(), shardIndex);
        Path path = createTempDir().resolve("indices").resolve(indexMeta.getIndexUUID()).resolve(String.valueOf(shardIndex));
        ShardRouting shardRouting = ShardRouting.newUnassigned(
            shardId,
            true,
            RecoverySource.EmptyStoreRecoverySource.INSTANCE,
            new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, null),
            ShardRouting.Role.DEFAULT
        );
        shardRouting = shardRouting.initialize(assignedShardNodeId, null, ShardRouting.UNAVAILABLE_EXPECTED_SHARD_SIZE);
        shardRouting = shardRouting.moveToStarted(ShardRouting.UNAVAILABLE_EXPECTED_SHARD_SIZE);
        CommonStats stats = new CommonStats();
        stats.docs = new DocsStats(100, 0, randomByteSizeValue().getBytes());
        stats.store = new StoreStats(shardDiskSize, 1, 1);
        stats.indexing = new IndexingStats(
            new IndexingStats.Stats(1, 1, 1, 1, 1, 1, 1, 1, 1, false, 1, 234, 234, 1000, 0.123, shardWriteLoad)
        );
        return new ShardStats(shardRouting, new ShardPath(false, path, path, shardId), stats, null, null, null, false, 0);
    }

    public void mockShardStatsForNode(ClusterState clusterState, String nodeName, List<ShardStats> shards) {
        MockTransportService.getInstance(nodeName)
            .addRequestHandlingBehavior(IndicesStatsAction.NAME + "[n]", (handler, request, channel, task) -> {
                TransportIndicesStatsAction instance = internalCluster().getInstance(TransportIndicesStatsAction.class, nodeName);
                channel.sendResponse(instance.new NodeResponse(getNodeId(nodeName), shards.size(), shards, List.of()));
            });
    }

    private void simulateHotSpottingOnNode(String nodeName, long queueLatencyThresholdMillis, float utilizationThreshold) {
        MockTransportService.getInstance(nodeName)
            .addRequestHandlingBehavior(TransportNodeUsageStatsForThreadPoolsAction.NAME + "[n]", (handler, request, channel, task) -> {
                handler.messageReceived(
                    request,
                    new TestTransportChannel(new ChannelActionListener<>(channel).delegateFailure((l, response) -> {
                        NodeUsageStatsForThreadPoolsAction.NodeResponse r = (NodeUsageStatsForThreadPoolsAction.NodeResponse) response;
                        l.onResponse(
                            new NodeUsageStatsForThreadPoolsAction.NodeResponse(
                                r.getNode(),
                                new NodeUsageStatsForThreadPools(
                                    r.getNodeUsageStatsForThreadPools().nodeId(),
                                    simulateWriteThreadPoolHotspotting(
                                        r.getNodeUsageStatsForThreadPools().threadPoolUsageStatsMap(),
                                        queueLatencyThresholdMillis,
                                        utilizationThreshold
                                    )
                                )
                            )
                        );
                    })),
                    task
                );
            });
    }

    private Map<String, NodeUsageStatsForThreadPools.ThreadPoolUsageStats> simulateWriteThreadPoolHotspotting(
        Map<String, NodeUsageStatsForThreadPools.ThreadPoolUsageStats> stringThreadPoolUsageStatsMap,
        long queueLatencyThresholdMillis,
        float utilizationThreshold
    ) {
        return stringThreadPoolUsageStatsMap.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> {
            NodeUsageStatsForThreadPools.ThreadPoolUsageStats originalStats = e.getValue();
            if (e.getKey().equals(ThreadPool.Names.WRITE)) {
                return new NodeUsageStatsForThreadPools.ThreadPoolUsageStats(
                    originalStats.totalThreadPoolThreads(),
                    randomFloatBetween(utilizationThreshold, 1.0f, true),
                    randomLongBetween(queueLatencyThresholdMillis * 2, queueLatencyThresholdMillis * 3)
                );
            }
            return originalStats;
        }));

    }

    public Settings createClusterInfoWriteLoadForecasterTestSettings(long queueLatencyThresholdMillis, int utilizationThresholdPercent) {
        final Settings settings = Settings.builder()
            // turn off all other balance settings and deciders
            .put(BalancedShardsAllocator.SHARD_BALANCE_FACTOR_SETTING.getKey(), 1.0f)
            .put(BalancedShardsAllocator.INDEX_BALANCE_FACTOR_SETTING.getKey(), 0.0f)
            .put(BalancedShardsAllocator.DISK_USAGE_BALANCE_FACTOR_SETTING.getKey(), 0.0f)
            .put(IndexBalanceConstraintSettings.INDEX_BALANCE_DECIDER_ENABLED_SETTING.getKey(), false)
            .put(BalancedShardsAllocator.THRESHOLD_SETTING.getKey(), 1.0f)
            .put(
                WriteLoadConstraintSettings.WRITE_LOAD_DECIDER_ENABLED_SETTING.getKey(),
                WriteLoadConstraintSettings.WriteLoadDeciderStatus.ENABLED
            )
            .put(
                WriteLoadConstraintSettings.WRITE_LOAD_DECIDER_QUEUE_LATENCY_THRESHOLD_SETTING.getKey(),
                TimeValue.timeValueMillis(queueLatencyThresholdMillis)
            )
            .put(
                WriteLoadConstraintSettings.WRITE_LOAD_DECIDER_HOTSPOT_UTILIZATION_THRESHOLD_SETTING.getKey(),
                utilizationThresholdPercent + "%"
            )
            .put(WriteLoadConstraintSettings.WRITE_LOAD_DECIDER_ALLOCATION_UTILIZATION_THRESHOLD_SETTING.getKey(), 90 + "%")
            .put(BalancedShardsAllocator.WRITE_LOAD_BALANCE_FACTOR_SETTING.getKey(), 1.0f)
            .put(WriteLoadForecasterPlugin.CLUSTER_INFO_WRITE_LOAD_FORECASTER_ENABLED_SETTING.getKey(), true)
            .put(WriteLoadConstraintSettings.WRITE_LOAD_DECIDER_SHARD_WRITE_LOAD_TYPE_SETTING.getKey(), "PEAK")
            .build();
        return settings;
    }

    public static class RebalanceDisablerPlugin extends Plugin implements ClusterPlugin {
        @Override
        public Collection<AllocationDecider> createAllocationDeciders(Settings settings, ClusterSettings clusterSettings) {
            return List.of(new AllocationDecider() {
                @Override
                public Decision canRebalance(RoutingAllocation allocation) {
                    return Decision.NO;
                }
            });
        }
    }
}
