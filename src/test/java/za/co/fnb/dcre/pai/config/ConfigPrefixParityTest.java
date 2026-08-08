package za.co.fnb.dcre.pai.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import za.co.fnb.dcre.pai.service.AccountInitService;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-48 GUARD, added with the AIS -> PAI rename (SCRUM-107).
 *
 * <p>The defect this exists for: a config-prefix rename that updates the Java
 * placeholder and leaves the yml key behind. Because every such placeholder
 * carries a constant default (<code>${dcre.pai.verdict-slice-size:10000}</code>),
 * the mismatch binds the DEFAULT instead of failing, so the context starts, the
 * whole suite passes and the build exits 0 while the configured value is dead.
 * It shipped once already. It very nearly shipped again in this rename: the
 * substitution updated <code>dcre.ais.</code> in Java but not the <code>ais:</code>
 * YAML nesting level, and nothing in the ported suite could see it.
 *
 * <p>The key is read from the ANNOTATION rather than written out here, so this
 * test cannot drift from the code it guards: renaming the prefix in Java without
 * renaming it in yml goes red by construction.
 */
class ConfigPrefixParityTest {

    private static final String YML = "application.yml";

    /**
     * Every defaulted placeholder the service declares must be SUPPLIED by
     * application.yml, not silently satisfied by its own fallback.
     */
    @Test
    void everyDefaultedPlaceholderIsBackedByAKeyInTheYml() throws IOException {
        final PropertySource<?> yml = loadYml();
        final List<String> placeholders = defaultedPlaceholdersOf(AccountInitService.class);

        assertFalse(placeholders.isEmpty(),
                "no defaulted @Value placeholders found: this guard would pass vacuously");

        for (final String key : placeholders) {
            assertNotNull(yml.getProperty(key), () ->
                    "R-48: '" + key + "' is declared in Java with a constant default but no such key"
                            + " exists in " + YML + ", so the default binds silently and the"
                            + " configured value is dead. Keys present: " + keysOf(yml));
        }
    }

    /** No key or value may still carry the pre-rename service code. */
    @Test
    void noPreRenameAisIdentifierSurvivesInTheYml() throws IOException {
        final PropertySource<?> yml = loadYml();
        for (final String key : keysOf(yml)) {
            final Object value = yml.getProperty(key);
            assertFalse(key.toLowerCase().contains("ais"),
                    "pre-rename identifier survives in " + YML + " key: " + key);
            assertFalse(String.valueOf(value).toLowerCase().contains("ais"),
                    "pre-rename identifier survives in " + YML + " value of " + key + ": " + value);
        }
    }

    /** Placeholder keys of the form ${key:default}; undefaulted ones fail loudly on their own. */
    private static List<String> defaultedPlaceholdersOf(final Class<?> type) {
        final List<String> keys = new ArrayList<>();
        for (final Constructor<?> ctor : type.getDeclaredConstructors()) {
            for (final Annotation[] onParam : ctor.getParameterAnnotations()) {
                for (final Annotation a : onParam) {
                    if (a instanceof Value v && v.value().startsWith("${") && v.value().contains(":")) {
                        keys.add(v.value().substring(2, v.value().indexOf(':')));
                    }
                }
            }
        }
        return keys;
    }

    private static PropertySource<?> loadYml() throws IOException {
        final List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(YML, new ClassPathResource(YML));
        assertTrue(!sources.isEmpty(), YML + " did not load from the classpath");
        return sources.getFirst();
    }

    private static List<String> keysOf(final PropertySource<?> source) {
        return List.of(((org.springframework.core.env.EnumerablePropertySource<?>) source)
                .getPropertyNames());
    }
}
