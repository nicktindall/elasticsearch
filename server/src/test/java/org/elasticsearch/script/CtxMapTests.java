/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.script;

import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.script.Metadata.LongField;
import static org.elasticsearch.script.Metadata.VERSION;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class CtxMapTests extends ESTestCase {
    CtxMap<?> map;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        map = new CtxMap<>(new HashMap<>(), new Metadata(Map.of(), Map.of()));
    }

    @SuppressWarnings("unchecked")
    public void testGetOrDefault() {
        {
            map = new CtxMap<>(Map.of("foo", "bar"), new Metadata(Map.of("_version", 5L), Map.of(VERSION, LongField.withWritable())));

            // it does the expected thing for fields that are present
            assertThat(map.getOrDefault("_version", -1L), equalTo(5L));
            assertThat(((Map<String, Object>) map.getOrDefault("_source", Map.of())).getOrDefault("foo", "wat"), equalTo("bar"));

            // it does the expected thing for fields that are not present
            assertThat(map.getOrDefault("_version_type", "something"), equalTo("something"));
            assertThat(map.getOrDefault("baz", "quux"), equalTo("quux"));
        }
        {
            map = new CtxMap<>(Map.of("foo", "bar"), new Metadata(Map.of("_version", 5L), Map.of(VERSION, LongField.withWritable()))) {
                @Override
                protected boolean directSourceAccess() {
                    return true;
                }
            };

            // it does the expected thing for fields that are present
            assertThat(map.getOrDefault("_version", -1L), equalTo(5L));
            assertThat(map.getOrDefault("foo", "wat"), equalTo("bar"));

            // it does the expected thing for fields that are not present
            assertThat(map.getOrDefault("_version_type", "something"), equalTo("something"));
            assertThat(map.getOrDefault("baz", "quux"), equalTo("quux"));
        }
    }

    public void testAddingJunkToCtx() {
        IllegalArgumentException err = expectThrows(IllegalArgumentException.class, () -> map.put("junk", "stuff"));
        assertEquals("Cannot put key [junk] with value [stuff] into ctx", err.getMessage());
    }

    public void testRemovingSource() {
        UnsupportedOperationException err = expectThrows(UnsupportedOperationException.class, () -> map.remove("_source"));
        assertEquals("Cannot remove key _source from ctx", err.getMessage());
        err = expectThrows(UnsupportedOperationException.class, () -> iteratorAtSource().remove());
        assertEquals("Cannot remove key [_source] from ctx", err.getMessage());
    }

    @SuppressWarnings("unchecked")
    public void testReplacingSource() {
        map.put("_source", Map.of("abc", 123));
        assertEquals(123, ((Map<String, Object>) map.get("_source")).get("abc"));
        sourceEntry().setValue(Map.of("def", 456));
        assertEquals(456, ((Map<String, Object>) map.get("_source")).get("def"));
    }

    public void testInvalidReplacementOfSource() {
        IllegalArgumentException err = expectThrows(
            IllegalArgumentException.class,
            () -> map.put("_source", List.of(1, 2, "buckle my shoe"))
        );
        assertThat(
            err.getMessage(),
            containsString("Expected [_source] to be a Map, not [[1, 2, buckle my shoe]] with type [java.util.ImmutableCollections$ListN]")
        );
        err = expectThrows(IllegalArgumentException.class, () -> sourceEntry().setValue(List.of(1, 2, "buckle my shoe")));
        assertThat(
            err.getMessage(),
            containsString("Expected [_source] to be a Map, not [[1, 2, buckle my shoe]] with type [java.util.ImmutableCollections$ListN]")
        );
    }

    protected Map.Entry<String, Object> sourceEntry() {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if ("_source".equals(entry.getKey())) {
                return entry;
            }
        }
        fail("no _source");
        return null;
    }

    protected Iterator<Map.Entry<String, Object>> iteratorAtSource() {
        Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if ("_source".equals(it.next().getKey())) {
                return it;
            }
        }
        fail("no _source");
        return null;
    }
}
