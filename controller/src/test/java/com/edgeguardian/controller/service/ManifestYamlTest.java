package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.DeviceManifestEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestYamlTest {

    @Test
    void parseExtractsMetadataAndSpec() {
        ManifestYaml.Parsed p = ManifestYaml.parse("""
                apiVersion: edgeguardian/v1
                kind: DeviceManifest
                metadata:
                  name: d1
                spec:
                  services:
                    - nginx
                """);
        assertEquals("d1", p.metadata().get("name"));
        assertTrue(p.spec().containsKey("services"));
    }

    @Test
    void parseDefaultsMissingSectionsToEmpty() {
        ManifestYaml.Parsed p = ManifestYaml.parse("apiVersion: edgeguardian/v1\nkind: DeviceManifest\n");
        assertTrue(p.metadata().isEmpty());
        assertTrue(p.spec().isEmpty());
    }

    @Test
    void parseRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> ManifestYaml.parse("   "));
    }

    @Test
    void parseRejectsInvalidYaml() {
        assertThrows(IllegalArgumentException.class, () -> ManifestYaml.parse("foo: [bar: }"));
    }

    @Test
    void parseRejectsNonMapping() {
        assertThrows(IllegalArgumentException.class, () -> ManifestYaml.parse("just a scalar string"));
    }

    @Test
    void toYamlRoundTrips() {
        DeviceManifestEntity e = new DeviceManifestEntity(
                "d1", Map.of("name", "d1"), Map.of("services", List.of("nginx")));
        String yaml = ManifestYaml.toYaml(e);

        assertTrue(yaml.contains("apiVersion"), yaml);
        assertTrue(yaml.contains("version"), yaml);
        ManifestYaml.Parsed p = ManifestYaml.parse(yaml);
        assertEquals("d1", p.metadata().get("name"));
        assertTrue(p.spec().containsKey("services"));
    }

    @Test
    void skeletonIsParseable() {
        ManifestYaml.Parsed p = ManifestYaml.parse(ManifestYaml.skeleton("d1"));
        assertTrue(p.spec() != null && p.metadata() != null);
    }
}
