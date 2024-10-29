/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.searchablesnapshots.s3;

import fixture.s3.S3HttpFixture;

import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.MutableSettingsProvider;
import org.elasticsearch.test.cluster.local.distribution.DistributionType;
import org.elasticsearch.xpack.searchablesnapshots.AbstractSearchableSnapshotsRestTestCase;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.mockito.internal.util.io.IOUtil;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;

public class S3SearchableSnapshotsIT extends AbstractSearchableSnapshotsRestTestCase {
    static final boolean USE_FIXTURE = Boolean.parseBoolean(System.getProperty("tests.use.fixture", "true"));

    public static final S3HttpFixture s3Fixture = new S3HttpFixture(USE_FIXTURE);
    private static final MutableSettingsProvider secureSettings = new MutableSettingsProvider();
    private static final String CLIENT = "default";
    private static final String KEYSTORE_PASSWORD = "topsecret";

    static {
        secureSettings.put("s3.client." + CLIENT + ".access_key", System.getProperty("s3AccessKey"));
        secureSettings.put("s3.client." + CLIENT + ".secret_key", System.getProperty("s3SecretKey"));
    }

    public static ElasticsearchCluster cluster = ElasticsearchCluster.local()
        .distribution(DistributionType.DEFAULT)
        .keystore(secureSettings)
        .keystorePassword(KEYSTORE_PASSWORD)
        .setting("xpack.license.self_generated.type", "trial")
        .setting("s3.client." + CLIENT + ".protocol", () -> "http", (n) -> USE_FIXTURE)
        .setting("s3.client." + CLIENT + ".endpoint", s3Fixture::getAddress, (n) -> USE_FIXTURE)
        .setting("xpack.searchable.snapshot.shared_cache.size", "16MB")
        .setting("xpack.searchable.snapshot.shared_cache.region_size", "256KB")
        .setting("xpack.searchable_snapshots.cache_fetch_async_thread_pool.keep_alive", "0ms")
        .setting("xpack.security.enabled", "false")
        .build();

    @ClassRule
    public static TestRule ruleChain = RuleChain.outerRule(s3Fixture).around(cluster);

    @Override
    protected String writeRepositoryType() {
        return "s3";
    }

    @Override
    protected Settings writeRepositorySettings() {
        final String bucket = System.getProperty("test.s3.bucket");
        assertThat(bucket, not(blankOrNullString()));

        final String basePath = System.getProperty("test.s3.base_path");
        assertThat(basePath, not(blankOrNullString()));

        return Settings.builder().put("client", CLIENT).put("bucket", bucket).put("base_path", basePath).build();
    }

    @Override
    protected String getTestRestCluster() {
        return cluster.getHttpAddresses();
    }

    @Test
    public void testCanChangeAccessKey() throws Exception {
        AtomicReference<String> indexName = new AtomicReference<>();
        int numDocs = randomIntBetween(10_000, 50_000);
        runSearchableSnapshotsTest((restoredIndexName, nd) -> {
            indexName.set(restoredIndexName);
            for (int i = 0; i < 10; i++) {
                assertSearchResults(restoredIndexName, numDocs, randomFrom(Boolean.TRUE, Boolean.FALSE, null));
            }
        }, false, numDocs, null, false);

        String newAccessKey = UUID.randomUUID().toString();
        s3Fixture.setAccessKey(newAccessKey);
        logger.info("--> Changed access key to [{}]", newAccessKey);

        // Set the value in the keystore, don't refresh
        secureSettings.put("s3.client." + CLIENT + ".access_key", newAccessKey);
        cluster.updateStoredSecureSettings();

        // Reload secure settings
        Request post = new Request("POST", "_nodes/reload_secure_settings");
        StringEntity entity = new StringEntity("{\"secure_settings_password\":\"" + KEYSTORE_PASSWORD + "\"}", StandardCharsets.UTF_8);
        entity.setContentType("application/json");
        post.setEntity(entity);
        Response response = client().performRequest(post);
        assertEquals(200, response.getStatusLine().getStatusCode());
        logger.info(
            "--> Got response {}\n{}",
            response.getStatusLine(),
            String.join("\n", IOUtil.readLines(response.getEntity().getContent()))
        );

        // Do some more interactions with the snapshot
        clearCache(indexName.get());
        for (int i = 0; i < 10; i++) {
            assertSearchResults(indexName.get(), numDocs, randomFrom(Boolean.TRUE, Boolean.FALSE, null));
        }
    }
}
