package za.co.fnb.dcre.mrv.config;

import org.junit.jupiter.api.Test;
import za.co.fnb.dcre.mrv.config.AcceptanceModeProperties.Mode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R-41 acceptance-mode resolution. modeFor falls to the default for an unmapped or
 * blank client, strips the token, and honours a mapped override.
 */
class AcceptanceModePropertiesTest {

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
