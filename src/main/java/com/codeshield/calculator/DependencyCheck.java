package com.codeshield.calculator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;

/**
 * Reports the version of each library actually loaded at runtime and runs a small self-test against it.
 * Used to show that the calculator still works after CodeShield upgrades a dependency.
 * All self-tests use constant data only, never caller input.
 */
final class DependencyCheck {

    private static final Logger LOG = LogManager.getLogger(DependencyCheck.class);

    record Result(String artifact, String version, String test, boolean ok, String error) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("library", artifact);
            m.put("version", version);
            m.put("selfTest", test);
            m.put("ok", ok);
            if (!ok) {
                m.put("error", error);
            }
            return m;
        }
    }

    private DependencyCheck() {
    }

    static List<Result> runAll() {
        List<Result> results = new ArrayList<>();

        results.add(check("org.apache.logging.log4j", "log4j-core", LogManager.class,
                "Write a log event", () -> {
                    LOG.info("Dependency self-test");
                    return true;
                }));

        results.add(check("com.fasterxml.jackson.core", "jackson-databind", ObjectMapper.class,
                "JSON round-trip", () -> {
                    ObjectMapper mapper = new ObjectMapper();
                    Map<?, ?> parsed = mapper.readValue("{\"result\":\"42\"}", Map.class);
                    return "{\"result\":\"42\"}".equals(mapper.writeValueAsString(parsed));
                }));

        results.add(check("org.apache.commons", "commons-lang3", StringUtils.class,
                "Trim and validate numbers", () ->
                        "12.5".equals(StringUtils.trimToEmpty("  12.5 "))
                        && org.apache.commons.lang3.math.NumberUtils.isCreatable("12.5")));

        results.add(check("org.apache.commons", "commons-text", StringEscapeUtils.class,
                "Escape HTML", () -> "&lt;b&gt;".equals(StringEscapeUtils.escapeHtml4("<b>"))));

        results.add(check("commons-io", "commons-io", IOUtils.class,
                "Read a stream", () -> "calc".equals(
                        IOUtils.toString(new ByteArrayInputStream("calc".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))));

        results.add(check("org.yaml", "snakeyaml", Yaml.class,
                "Parse YAML", () -> {
                    Object loaded = new Yaml().load("precision: 16");
                    return loaded instanceof Map<?, ?> m && Integer.valueOf(16).equals(m.get("precision"));
                }));

        results.add(check("com.google.guava", "guava", Strings.class,
                "Join strings", () -> Strings.isNullOrEmpty("") && "+,-".equals(Joiner.on(',').join(List.of("+", "-")))));

        return results;
    }

    private static Result check(String groupId, String artifactId, Class<?> marker, String test, Callable<Boolean> probe) {
        String version = version(groupId, artifactId, marker);
        try {
            boolean ok = Boolean.TRUE.equals(probe.call());
            return new Result(artifactId, version, test, ok, ok ? "" : "unexpected result");
        } catch (Throwable t) {   // incl. NoSuchMethodError etc. from an incompatible upgrade
            return new Result(artifactId, version, test, false, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Version from the jar's Maven metadata, falling back to the jar manifest. */
    private static String version(String groupId, String artifactId, Class<?> marker) {
        String path = "/META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        try (InputStream in = marker.getResourceAsStream(path)) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        } catch (Exception ignored) {
            // fall through to manifest
        }
        Package pkg = marker.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        return v == null || v.isBlank() ? "unknown" : v;
    }
}
