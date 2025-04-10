/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.admin.indices.create;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetadataCreateIndexService;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.indices.SystemIndexDescriptor;
import org.elasticsearch.indices.SystemIndices;
import org.elasticsearch.indices.SystemIndices.SystemIndexAccessLevel;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_INDEX_HIDDEN;

/**
 * Create index action.
 */
public class TransportCreateIndexAction extends TransportMasterNodeAction<CreateIndexRequest, CreateIndexResponse> {
    public static final ActionType<CreateIndexResponse> TYPE = new ActionType<>("indices:admin/create");
    private static final Logger logger = LogManager.getLogger(TransportCreateIndexAction.class);

    private final MetadataCreateIndexService createIndexService;
    private final SystemIndices systemIndices;
    private final ProjectResolver projectResolver;

    @Inject
    public TransportCreateIndexAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        MetadataCreateIndexService createIndexService,
        ActionFilters actionFilters,
        SystemIndices systemIndices,
        ProjectResolver projectResolver
    ) {
        super(
            TYPE.name(),
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            CreateIndexRequest::new,
            CreateIndexResponse::new,
            EsExecutors.DIRECT_EXECUTOR_SERVICE
        );
        this.createIndexService = createIndexService;
        this.systemIndices = systemIndices;
        this.projectResolver = projectResolver;
    }

    @Override
    protected ClusterBlockException checkBlock(CreateIndexRequest request, ClusterState state) {
        return state.blocks().indexBlockedException(projectResolver.getProjectId(), ClusterBlockLevel.METADATA_WRITE, request.index());
    }

    @Override
    protected void masterOperation(
        Task task,
        final CreateIndexRequest request,
        final ClusterState state,
        final ActionListener<CreateIndexResponse> listener
    ) {
        String cause = request.cause();
        if (cause.isEmpty()) {
            cause = "api";
        }

        final long resolvedAt = System.currentTimeMillis();
        final String indexName = IndexNameExpressionResolver.resolveDateMathExpression(request.index(), resolvedAt);

        final SystemIndexDescriptor mainDescriptor = systemIndices.findMatchingDescriptor(indexName);
        final boolean isSystemIndex = mainDescriptor != null;
        final boolean isManagedSystemIndex = isSystemIndex && mainDescriptor.isAutomaticallyManaged();
        if (mainDescriptor != null && mainDescriptor.isNetNew()) {
            final SystemIndexAccessLevel systemIndexAccessLevel = SystemIndices.getSystemIndexAccessLevel(threadPool.getThreadContext());
            if (systemIndexAccessLevel != SystemIndexAccessLevel.ALL) {
                if (systemIndexAccessLevel == SystemIndexAccessLevel.RESTRICTED) {
                    if (systemIndices.getProductSystemIndexNamePredicate(threadPool.getThreadContext()).test(indexName) == false) {
                        throw SystemIndices.netNewSystemIndexAccessException(threadPool.getThreadContext(), List.of(indexName));
                    }
                } else {
                    // BACKWARDS_COMPATIBLE_ONLY should never be a possibility here, it cannot be returned from getSystemIndexAccessLevel
                    assert systemIndexAccessLevel == SystemIndexAccessLevel.NONE
                        : "Expected no system index access but level is " + systemIndexAccessLevel;
                    throw SystemIndices.netNewSystemIndexAccessException(threadPool.getThreadContext(), List.of(indexName));
                }
            }
        }

        if (isSystemIndex) {
            if (Objects.isNull(request.settings())) {
                request.settings(SystemIndexDescriptor.DEFAULT_SETTINGS);
            } else if (false == request.settings().hasValue(SETTING_INDEX_HIDDEN)) {
                request.settings(Settings.builder().put(request.settings()).put(SETTING_INDEX_HIDDEN, true).build());
            } else if (Boolean.FALSE.toString().equalsIgnoreCase(request.settings().get(SETTING_INDEX_HIDDEN))) {
                final String message = "Cannot create system index [" + indexName + "] with [index.hidden] set to 'false'";
                logger.warn(message);
                listener.onFailure(new IllegalStateException(message));
                return;
            }
        }

        // TODO: This really needs the ID. But the current test depends on it going through the metadata to trigger more checks
        final ProjectId projectId = projectResolver.getProjectMetadata(state.metadata()).id();
        final CreateIndexClusterStateUpdateRequest updateRequest;

        // Requests that a cluster generates itself are permitted to create a system index with
        // different mappings, settings etc. This is so that rolling upgrade scenarios still work.
        // We check this via the request's origin. Eventually, `SystemIndexManager` will reconfigure
        // the index to the latest settings.
        if (isManagedSystemIndex && Strings.isNullOrEmpty(request.origin())) {
            final var requiredMinimumMappingVersion = state.getMinSystemIndexMappingVersions().get(mainDescriptor.getPrimaryIndex());
            final SystemIndexDescriptor descriptor = mainDescriptor.getDescriptorCompatibleWith(requiredMinimumMappingVersion);
            if (descriptor == null) {
                final String message = mainDescriptor.getMinimumMappingsVersionMessage("create index", requiredMinimumMappingVersion);
                logger.warn(message);
                listener.onFailure(new IllegalStateException(message));
                return;
            }
            updateRequest = buildManagedSystemIndexUpdateRequest(request, cause, descriptor, projectId);
        } else {
            updateRequest = buildUpdateRequest(request, cause, indexName, resolvedAt, projectId);
        }

        createIndexService.createIndex(
            request.masterNodeTimeout(),
            request.ackTimeout(),
            request.ackTimeout(),
            updateRequest,
            listener.map(response -> new CreateIndexResponse(response.isAcknowledged(), response.isShardsAcknowledged(), indexName))
        );
    }

    private CreateIndexClusterStateUpdateRequest buildUpdateRequest(
        CreateIndexRequest request,
        String cause,
        String indexName,
        long nameResolvedAt,
        ProjectId projectId
    ) {
        Set<Alias> aliases = request.aliases().stream().peek(alias -> {
            if (systemIndices.isSystemName(alias.name())) {
                alias.isHidden(true);
            }
        }).collect(Collectors.toSet());
        return new CreateIndexClusterStateUpdateRequest(cause, projectId, indexName, request.index()).settings(request.settings())
            .mappings(request.mappings())
            .aliases(aliases)
            .nameResolvedInstant(nameResolvedAt)
            .waitForActiveShards(request.waitForActiveShards());
    }

    private static CreateIndexClusterStateUpdateRequest buildManagedSystemIndexUpdateRequest(
        CreateIndexRequest request,
        String cause,
        SystemIndexDescriptor descriptor,
        ProjectId projectId
    ) {
        boolean indexMigrationInProgress = cause.equals(SystemIndices.MIGRATE_SYSTEM_INDEX_CAUSE)
            && request.index().endsWith(SystemIndices.UPGRADED_INDEX_SUFFIX);

        final Settings settings;
        final String mappings;
        final Set<Alias> aliases;
        final String indexName;

        // if we are migrating a system index to a new index, we use settings/mappings/index name from the request,
        // since it was created by SystemIndexMigrator
        if (indexMigrationInProgress) {
            settings = request.settings();
            mappings = request.mappings();
            indexName = request.index();
            // we will update alias later on
            aliases = Set.of();
        } else {
            settings = Objects.requireNonNullElse(descriptor.getSettings(), Settings.EMPTY);
            mappings = descriptor.getMappings();

            if (descriptor.getAliasName() == null) {
                aliases = Set.of();
            } else {
                aliases = Set.of(new Alias(descriptor.getAliasName()).isHidden(true).writeIndex(true));
            }

            // Throw an error if we are trying to directly create a system index other
            // than the primary system index (or the alias, or we are migrating the index)
            if (request.index().equals(descriptor.getPrimaryIndex()) == false
                && request.index().equals(descriptor.getAliasName()) == false) {
                throw new IllegalArgumentException(
                    "Cannot create system index with name "
                        + request.index()
                        + "; descriptor primary index is "
                        + descriptor.getPrimaryIndex()
                );
            }
            indexName = descriptor.getPrimaryIndex();
        }

        return new CreateIndexClusterStateUpdateRequest(cause, projectId, indexName, request.index()).aliases(aliases)
            .waitForActiveShards(ActiveShardCount.ALL)
            .mappings(mappings)
            .settings(settings);
    }
}
