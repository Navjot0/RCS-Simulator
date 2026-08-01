package com.jio.rcs.operator.unit.wire;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jio.rcs.operator.config.instance.InstanceConfig;
import com.jio.rcs.operator.config.instance.InstanceConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link InstanceConfigLoader}'s directory-scanning, parsing, and
 * validation behaviour directly against a real (JUnit {@code @TempDir})
 * filesystem directory - the multi-instance DLR callback routing spec's
 * scenarios that are specifically about *loading* instance JSON files,
 * as opposed to the URL-resolution logic itself (covered by {@code
 * CallbackUrlResolverTest} against an already-built {@code InstanceRegistry}).
 */
class InstanceConfigLoaderTest {

    private final InstanceConfigLoader loader = new InstanceConfigLoader(new ObjectMapper());

    private void write(Path dir, String filename, String content) throws IOException {
        Files.writeString(dir.resolve(filename), content);
    }

    // 1: load a single instance file
    @Test
    void loadsSingleInstanceFile(@TempDir Path dir) throws IOException {
        write(dir, "dev.json", """
                { "instance": "dev", "enabled": true,
                  "profiles": { "vi": { "callbackUrl": "https://dev.example.com/vi" } } }
                """);

        Map<String, InstanceConfig> loaded = loader.loadAll(dir);

        assertThat(loaded).containsOnlyKeys("dev");
        assertThat(loaded.get("dev").isEnabled()).isTrue();
        assertThat(loaded.get("dev").getProfiles().get("vi").getCallbackUrl()).isEqualTo("https://dev.example.com/vi");
    }

    // 2: load multiple instance files
    @Test
    void loadsMultipleInstanceFiles(@TempDir Path dir) throws IOException {
        write(dir, "dev.json", "{ \"instance\": \"dev\", \"enabled\": true, \"profiles\": {} }");
        write(dir, "staging.json", "{ \"instance\": \"staging\", \"enabled\": true, \"profiles\": {} }");
        write(dir, "cerf.json", "{ \"instance\": \"cerf\", \"enabled\": true, \"profiles\": {} }");

        Map<String, InstanceConfig> loaded = loader.loadAll(dir);

        assertThat(loaded).containsOnlyKeys("dev", "staging", "cerf");
    }

    // 3: an arbitrary new instance (e.g. "uat") works with no Java/routing change -
    // the loader has no notion of which instance names exist ahead of time.
    @Test
    void arbitraryNewInstanceFileWorksWithoutAnyCodeChange(@TempDir Path dir) throws IOException {
        write(dir, "uat.json", """
                { "instance": "uat", "enabled": true,
                  "profiles": { "vi": { "callbackUrl": "https://uat.example.com/vi/dlr" } } }
                """);

        Map<String, InstanceConfig> loaded = loader.loadAll(dir);

        assertThat(loaded).containsKey("uat");
        assertThat(loaded.get("uat").getProfiles().get("vi").getCallbackUrl()).isEqualTo("https://uat.example.com/vi/dlr");
    }

    // Non-existent directory: not a hard failure, just "nothing loaded".
    @Test
    void missingDirectoryYieldsNoInstancesWithoutThrowing(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist");

        Map<String, InstanceConfig> loaded = loader.loadAll(missing);

        assertThat(loaded).isEmpty();
    }

    // 16: malformed JSON is handled clearly
    @Test
    void malformedJsonIsRejectedClearly(@TempDir Path dir) throws IOException {
        write(dir, "broken.json", "{ this is not valid json ");

        assertThatThrownBy(() -> loader.loadAll(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken.json");
    }

    // 17: duplicate instance ids across files are detected
    @Test
    void duplicateInstanceIdsAreDetected(@TempDir Path dir) throws IOException {
        write(dir, "dev.json", "{ \"instance\": \"dev\", \"enabled\": true, \"profiles\": {} }");
        write(dir, "dev-copy.json", "{ \"instance\": \"dev\", \"enabled\": true, \"profiles\": {} }");

        assertThatThrownBy(() -> loader.loadAll(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate instance id")
                .hasMessageContaining("dev");
    }

    // 18: non-JSON files in the directory are ignored
    @Test
    void nonJsonFilesAreIgnored(@TempDir Path dir) throws IOException {
        write(dir, "dev.json", "{ \"instance\": \"dev\", \"enabled\": true, \"profiles\": {} }");
        write(dir, "README.md", "not an instance file");
        write(dir, "notes.txt", "also not an instance file");

        Map<String, InstanceConfig> loaded = loader.loadAll(dir);

        assertThat(loaded).containsOnlyKeys("dev");
    }

    @Test
    void blankInstanceNameIsRejected(@TempDir Path dir) throws IOException {
        write(dir, "blank.json", "{ \"instance\": \"\", \"enabled\": true, \"profiles\": {} }");

        assertThatThrownBy(() -> loader.loadAll(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank.json");
    }

    @Test
    void missingInstanceFieldIsRejected(@TempDir Path dir) throws IOException {
        write(dir, "noname.json", "{ \"enabled\": true, \"profiles\": {} }");

        assertThatThrownBy(() -> loader.loadAll(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("noname.json");
    }

    @Test
    void invalidInstanceNameCharactersAreRejected(@TempDir Path dir) throws IOException {
        write(dir, "bad.json", "{ \"instance\": \"has a space\", \"enabled\": true, \"profiles\": {} }");

        assertThatThrownBy(() -> loader.loadAll(dir))
                .isInstanceOf(IllegalStateException.class);
    }

    // Invalid (non-http/https, malformed) callback URL is rejected clearly at load time.
    @Test
    void invalidCallbackUrlIsRejected(@TempDir Path dir) throws IOException {
        write(dir, "dev.json", """
                { "instance": "dev", "enabled": true,
                  "profiles": { "vi": { "callbackUrl": "not-a-url" } } }
                """);

        assertThatThrownBy(() -> loader.loadAll(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vi");
    }

    // A blank callbackUrl is a valid (if incomplete) configuration shape at
    // load time - only rejected later, at request time, by InstanceRegistry.
    @Test
    void blankCallbackUrlDoesNotFailLoading(@TempDir Path dir) throws IOException {
        write(dir, "uat.json", """
                { "instance": "uat", "enabled": true,
                  "profiles": { "jio": { "callbackUrl": "" } } }
                """);

        Map<String, InstanceConfig> loaded = loader.loadAll(dir);

        assertThat(loaded.get("uat").getProfiles().get("jio").getCallbackUrl()).isEmpty();
    }

    // A provider simply absent from "profiles" is a valid, expected shape
    // (item 19 of the spec: an instance need not support every provider).
    @Test
    void instanceWithOnlyOneProviderConfiguredLoadsSuccessfully(@TempDir Path dir) throws IOException {
        write(dir, "uat.json", """
                { "instance": "uat", "enabled": true,
                  "profiles": { "vi": { "callbackUrl": "https://uat.example.com/vi/dlr" } } }
                """);

        Map<String, InstanceConfig> loaded = loader.loadAll(dir);

        assertThat(loaded.get("uat").getProfiles()).containsOnlyKeys("vi");
    }

    // Disabled instance loads fine at startup - it's rejected only at request time.
    @Test
    void disabledInstanceLoadsSuccessfully(@TempDir Path dir) throws IOException {
        write(dir, "dev.json", "{ \"instance\": \"dev\", \"enabled\": false, \"profiles\": {} }");

        Map<String, InstanceConfig> loaded = loader.loadAll(dir);

        assertThat(loaded.get("dev").isEnabled()).isFalse();
    }
}
