package za.co.fnb.dcre.mrv.service;

import org.springframework.stereotype.Component;
import za.co.fnb.dcre.mrv.domain.AccountReferenceLoadFailure;
import za.co.fnb.dcre.mrv.domain.AccountReferenceManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The artifact directory as a whole: manifest first, rows second, so the caller can refuse
 * on a version mismatch before spending a checksum on bytes it has already decided not to
 * trust. The split is the contract's step order, not an optimisation.
 */
@Component
public class AccountArtifactReader {

    static final String MANIFEST = "manifest.properties";
    static final String CSV = "account.csv";

    private final AccountManifestReader manifests;
    private final AccountCsvReader rows;

    public AccountArtifactReader(final AccountManifestReader manifests, final AccountCsvReader rows) {
        this.manifests = manifests;
        this.rows = rows;
    }

    /**
     * Step 1 and 2: the directory must exist and be a directory, and its manifest must
     * parse. An absent artifact NEVER degrades to "carry on with whatever is in the table":
     * that is the fail-open this whole wave removes.
     */
    public AccountReferenceManifest readManifest(final Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new AccountReferenceLoadFailure(
                    "account reference artifact directory is absent or not a directory: " + directory);
        }
        return manifests.read(directory.resolve(MANIFEST));
    }

    /** Steps 5 and 6: checksum the bytes, then parse and count them. */
    public List<Map<String, String>> readRows(final Path directory, final AccountReferenceManifest manifest) {
        return rows.read(directory.resolve(CSV), manifest);
    }
}
