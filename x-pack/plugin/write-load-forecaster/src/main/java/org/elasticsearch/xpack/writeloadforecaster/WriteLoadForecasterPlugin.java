/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.writeloadforecaster;

import org.elasticsearch.cluster.ClusterInfoService;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.routing.allocation.WriteLoadForecaster;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.license.License;
import org.elasticsearch.license.LicensedFeature;
import org.elasticsearch.plugins.ClusterPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.XPackPlugin;

import java.util.Collection;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;

import static org.elasticsearch.xpack.writeloadforecaster.LicensedWriteLoadForecaster.MAX_INDEX_AGE_SETTING;

public class WriteLoadForecasterPlugin extends Plugin implements ClusterPlugin {

    public static final LicensedFeature.Momentary WRITE_LOAD_FORECAST_FEATURE = LicensedFeature.momentary(
        null,
        "write-load-forecast",
        License.OperationMode.ENTERPRISE
    );

    public static final Setting<Double> OVERRIDE_WRITE_LOAD_FORECAST_SETTING = Setting.doubleSetting(
        "index.override_write_load_forecast",
        0.0,
        0.0,
        Setting.Property.Dynamic,
        Setting.Property.IndexScope
    );

    public static final Setting<Boolean> CLUSTER_INFO_WRITE_LOAD_FORECASTER_ENABLED_SETTING = Setting.boolSetting(
        "cluster_info_write_load_forecaster.enabled",
        false,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public WriteLoadForecasterPlugin() {}

    protected boolean hasValidLicense() {
        return WRITE_LOAD_FORECAST_FEATURE.check(XPackPlugin.getSharedLicenseState());
    }

    @Override
    public List<Setting<?>> getSettings() {
        return List.of(MAX_INDEX_AGE_SETTING, OVERRIDE_WRITE_LOAD_FORECAST_SETTING, CLUSTER_INFO_WRITE_LOAD_FORECASTER_ENABLED_SETTING);
    }

    @Override
    public Collection<WriteLoadForecaster> createWriteLoadForecasters(
        ThreadPool threadPool,
        Settings settings,
        ClusterSettings clusterSettings
    ) {
        /**
         * Return a wrapper forecaster around a delegate WriteLoadForecaster, where the wrapper switches between
         * ClusterInfoWriteLoadForecaster and LicensedWriteLoadForecaster as the setting CLUSTER_INFO_WRITE_LOAD_FORECASTER_ENABLED_SETTING
         * changes. This extra layer is needed, because createWriteLoadForecaster is only called during node setup */
        return List.of(new DelegateDynamicSettingsChangerWriteLoadForecaster(threadPool, settings, clusterSettings, this::hasValidLicense));
    }

    public static class DelegateDynamicSettingsChangerWriteLoadForecaster implements WriteLoadForecaster {
        private final ClusterInfoWriteLoadForecaster clusterInfoForecaster;
        private final LicensedWriteLoadForecaster licensedForecaster;

        private volatile WriteLoadForecaster delegateForecaster;
        private volatile boolean clusterInfoWriteLoadForecasterEnabled;

        public DelegateDynamicSettingsChangerWriteLoadForecaster(
            final ThreadPool threadPool,
            final Settings settings,
            final ClusterSettings clusterSettings,
            final BooleanSupplier licenseCheck
        ) {
            this.clusterInfoForecaster = new ClusterInfoWriteLoadForecaster(licenseCheck);
            this.licensedForecaster = new LicensedWriteLoadForecaster(licenseCheck, threadPool, settings, clusterSettings);

            // set up with initial forecaster
            clusterInfoWriteLoadForecasterEnabled = false;
            handleChangedWriteLoadForecaster();

            clusterSettings.initializeAndWatch(CLUSTER_INFO_WRITE_LOAD_FORECASTER_ENABLED_SETTING, value -> {
                boolean oldClusterInfoWriteLoadForecasterEnabled = clusterInfoWriteLoadForecasterEnabled;
                clusterInfoWriteLoadForecasterEnabled = value;
                if (clusterInfoWriteLoadForecasterEnabled != oldClusterInfoWriteLoadForecasterEnabled) {
                    handleChangedWriteLoadForecaster();
                }
            });
        }

        private void handleChangedWriteLoadForecaster() {
            if (clusterInfoWriteLoadForecasterEnabled) {
                delegateForecaster = clusterInfoForecaster;
            } else {
                delegateForecaster = licensedForecaster;
            }
        }

        @Inject
        public void setClusterInfoService(ClusterInfoService clusterInfoService) {
            clusterInfoService.addListener(this.clusterInfoForecaster::onNewClusterInfo);
        }

        @Override
        public ProjectMetadata.Builder withWriteLoadForecastForWriteIndex(String dataStreamName, ProjectMetadata.Builder metadata) {
            return delegateForecaster.withWriteLoadForecastForWriteIndex(dataStreamName, metadata);
        }

        @Override
        public OptionalDouble getForecastedWriteLoad(IndexMetadata indexMetadata) {
            return delegateForecaster.getForecastedWriteLoad(indexMetadata);
        }

        @Override
        public void refreshLicense() {
            delegateForecaster.refreshLicense();
        }
    }
}
