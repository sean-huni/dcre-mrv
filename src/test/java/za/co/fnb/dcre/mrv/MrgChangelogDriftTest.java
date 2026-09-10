package za.co.fnb.dcre.mrv;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The drift guard on the MRG-owned changesets MRV copies into its test fixture
 * ({@code src/test/resources/mrg-changelog/}). MRV reads relations it does not own, so the
 * fixture has to be MRG's definition, not a paraphrase of it: the paraphrase this replaces
 * drifted silently and hid the grain defect SCRUM-91 fixes (the 1:1-live check reading the
 * per-INSTRUCTION view instead of the per-MANDATE collapse).
 *
 * <p>The comparison needs MRG checked out beside MRV, which is how the fleet is laid out.
 * When it is not (a standalone clone), the check is SKIPPED rather than failed: an absent
 * sibling repo is not evidence of drift. It is a guard against silent divergence on the
 * machines that change MRG, which is where divergence is introduced.
 */
class MrgChangelogDriftTest {

    private static final Path FIXTURE = Path.of("src", "test", "resources", "mrg-changelog");
    private static final Path MRG = Path.of("..", "mrg", "src", "main", "resources", "db", "changelog", "2026", "07");

    private static final List<String> COPIED = List.of(
            "001-man-spine-bootstrap.xml", "004-man-views.xml",
            "005-man-effective.xml", "007-man-current-status.xml");

    /**
     * MRG-internal: the core bootstrap MRV owns its own copy of, batch metadata, reporting and
     * the status-history audit. SCRUM-107 removed 006-drop-man-ext-status.xml and
     * 009-drop-mandate-projection.xml from this list along with the files themselves: the v1
     * baseline mints neither shape, so it has no teardown to exempt.
     */
    private static final List<String> NOT_READ_BY_MRV = List.of(
            "000-man-core-bootstrap.xml", "002-batch-metadata.xml", "003-man-reporting.xml",
            "008-man-status-history.xml");

    @Test
    void everyCopiedChangelogIsByteIdenticalToMrgsOwn() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(MRG),
                "MRG is not checked out beside MRV; the fixture cannot be compared to its source");

        for (final String file : COPIED) {
            assertEquals(Files.readString(MRG.resolve(file)), Files.readString(FIXTURE.resolve(file)),
                    file + " has drifted from MRG's changelog: re-copy it and re-check every"
                            + " assumption MRV's tests make about that relation");
        }

        // A byte compare of the copies cannot see a changeset MRG ADDED, which is exactly how a
        // later redefinition of mandate_current_status would slip past this fixture unnoticed.
        try (var listing = Files.list(MRG)) {
            final List<String> unaccounted = listing.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".xml"))
                    .filter(name -> !COPIED.contains(name) && !NOT_READ_BY_MRV.contains(name))
                    .sorted().toList();
            assertEquals(List.of(), unaccounted,
                    "MRG ships changesets this fixture knows nothing about: decide for each whether"
                            + " MRV reads the relation it defines, then copy it or list it as not read");
        }
    }
}
