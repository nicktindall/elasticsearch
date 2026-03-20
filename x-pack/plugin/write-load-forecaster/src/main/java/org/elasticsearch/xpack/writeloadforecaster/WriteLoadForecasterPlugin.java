/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.writeloadforecaster;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.routing.allocation.WriteLoadForecaster;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.License;
import org.elasticsearch.license.LicensedFeature;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.plugins.ClusterPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.XPackPlugin;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.elasticsearch.xpack.writeloadforecaster.LicensedWriteLoadForecaster.MAX_INDEX_AGE_SETTING;

public class WriteLoadForecasterPlugin extends Plugin implements ClusterPlugin {
    private static final Logger logger = LogManager.getLogger(WriteLoadForecasterPlugin.class);

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
        final boolean clusterInfoWriteLoadForecasterEnabled = CLUSTER_INFO_WRITE_LOAD_FORECASTER_ENABLED_SETTING.get(settings);
        if (clusterInfoWriteLoadForecasterEnabled) {
            return List.of(new LicenseCheckingWriteLoadForecaster(new ClusterInfoWriteLoadForecaster(), this::hasValidLicense));
        } else {
            return List.of(new LicenseCheckingWriteLoadForecaster(new LicensedWriteLoadForecaster(threadPool, settings, clusterSettings),
                this::hasValidLicense));
        }
    }

    public static class LicenseCheckingWriteLoadForecaster implements WriteLoadForecaster {
        private final WriteLoadForecaster delegate;
        private final BooleanSupplier hasValidLicenseSupplier;

        @SuppressWarnings("unused") // modified via VH_HAS_VALID_LICENSE_FIELD
        private volatile boolean hasValidLicense;

        public LicenseCheckingWriteLoadForecaster(WriteLoadForecaster delegate, BooleanSupplier licenseSupplier) {
            this.delegate = delegate;
            this.hasValidLicenseSupplier = licenseSupplier;
        }

        @Override
        public ProjectMetadata.Builder withWriteLoadForecastForWriteIndex(String dataStreamName, ProjectMetadata.Builder metadata) {
            if (hasValidLicense == false) {
                return metadata;
            }
            return this.delegate.withWriteLoadForecastForWriteIndex(dataStreamName, metadata);
        }

        @Override
        public OptionalDouble getForecastedWriteLoad(IndexMetadata indexMetadata) {
            if (hasValidLicense == false) {
                return OptionalDouble.empty();
            }
            return this.delegate.getForecastedWriteLoad(indexMetadata);
        }

        /**
         * Used to atomically {@code getAndSet()} the {@link #hasValidLicense} field. This is better than an
         * {@link java.util.concurrent.atomic.AtomicBoolean} because it takes one less pointer dereference on each read.
         */
        private static final VarHandle VH_HAS_VALID_LICENSE_FIELD;

        static {
            try {
                VH_HAS_VALID_LICENSE_FIELD = MethodHandles.lookup()
                    .in(WriteLoadForecasterPlugin.LicenseCheckingWriteLoadForecaster.class)
                    .findVarHandle(WriteLoadForecasterPlugin.LicenseCheckingWriteLoadForecaster.class, "hasValidLicense", boolean.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void refreshLicense() {
            final var newValue = hasValidLicenseSupplier.getAsBoolean();
            final var oldValue = (boolean) VH_HAS_VALID_LICENSE_FIELD.getAndSet(this, newValue);
            if (newValue != oldValue) {
                logger.info("license state changed, now [{}]", newValue ? "valid" : "not valid");
            }
        }
    }
}
