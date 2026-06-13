package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.DeviceManifestEntity;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts device manifests between the editor's YAML text and the stored
 * {@link DeviceManifestEntity}. Only {@code metadata} and {@code spec} are
 * authored by users; {@code apiVersion}, {@code kind}, and {@code version} are
 * managed by the controller.
 */
public final class ManifestYaml {

    private ManifestYaml() {}

    public record Parsed(Map<String, Object> metadata, Map<String, Object> spec) {}

    public static Parsed parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Manifest content is empty");
        }
        Object loaded;
        try {
            loaded = new Yaml().load(content);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid YAML: " + e.getMessage());
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Manifest must be a YAML mapping with metadata/spec");
        }
        return new Parsed(asMap(root.get("metadata")), asMap(root.get("spec")));
    }

    public static String toYaml(DeviceManifestEntity m) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("apiVersion", m.getApiVersion());
        root.put("kind", m.getKind());
        root.put("metadata", m.getMetadata() != null ? m.getMetadata() : Map.of());
        root.put("spec", m.getSpec() != null ? m.getSpec() : Map.of());
        root.put("version", m.getVersion());
        return dump(root);
    }

    public static String skeleton(String deviceId) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("apiVersion", "edgeguardian/v1");
        root.put("kind", "DeviceManifest");
        root.put("metadata", new LinkedHashMap<>(Map.of("name", deviceId)));
        root.put("spec", new LinkedHashMap<>(Map.of("files", java.util.List.of(), "services", java.util.List.of())));
        return dump(root);
    }

    private static String dump(Map<String, Object> root) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        return new Yaml(opts).dump(root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }
}
