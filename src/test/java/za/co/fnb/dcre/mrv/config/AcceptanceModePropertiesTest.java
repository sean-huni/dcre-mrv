package za.co.fnb.dcre.mrv.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import za.co.fnb.dcre.mrv.config.AcceptanceModeProperties.Mode;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-41 acceptance-mode resolution. modeFor falls to the default for an unmapped or
 * blank client, strips the token, and honours a mapped override.
 */
class AcceptanceModePropertiesTest {

    /**
     * Regression (T16 live gate): the shipped application.yml must bind through the
     * same OriginTrackedYamlLoader Spring Boot uses at startup. An empty flow map
     * {@code clients: {}} is loaded as the STRING "" (no String->Map converter),
     * crashing the context on the first live pod launch; a bare {@code clients:}
     * (null) leaves the field's HashMap default. This test loads the real file so
     * the failure is caught in the build, not in the cluster.
     */
    @Test
    void shippedYamlBindsEmptyClientMap() throws IOException {
        final StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(source -> env.getPropertySources().addLast(source));

        // Bindable.ofInstance mirrors how @ConfigurationProperties binds onto the
        // existing bean: a null key leaves the field's HashMap default, an empty
        // flow map {} binds the String "" and fails. Binder.bind(prefix, Class)
        // constructs a fresh instance and would not reflect the live failure mode.
        final AcceptanceModeProperties props = Binder.get(env)
                .bind("dcre.mrv.acceptance-mode", Bindable.ofInstance(new AcceptanceModeProperties()))
                .get();

        assertEquals(Mode.ALL_OR_NOTHING, props.getDefault(), "shipped default");
        assertTrue(props.getClients().isEmpty(), "empty client map must bind, not ConverterNotFound on \"\"");
    }

    @Test
    void resolvesDefaultAndMappedOverride() {
        final AcceptanceModeProperties props = new AcceptanceModeProperties();
        props.getClients().put("FNBRF01", Mode.PARTIAL);

        assertEquals(Mode.ALL_OR_NOTHING, props.modeFor("FNBCC01"), "unmapped -> default");
        assertEquals(Mode.ALL_OR_NOTHING, props.modeFor(""), "blank -> default");
        assertEquals(Mode.ALL_OR_NOTHING, props.modeFor(null), "null -> default");
        assertEquals(Mode.PARTIAL, props.modeFor("FNBRF01"), "mapped override");
        assertEquals(Mode.PARTIAL, props.modeFor(" FNBRF01 "), "stripped");
    }
}
