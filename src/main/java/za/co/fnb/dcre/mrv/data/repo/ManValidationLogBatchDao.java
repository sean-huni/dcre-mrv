package za.co.fnb.dcre.mrv.data.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.mrv.data.model.ManValidationLogEntity;

import java.util.List;

/**
 * Sole writer of man_validation_log: one JDBC batch per validation run. The
 * guarded INSERT ... ON CONFLICT (arrival_id, sequence) DO NOTHING makes the
 * verdict first-write-wins on the FULL business identity, so a resume re-run is a
 * zero-duplicate no-op (never UPSERT on the PK; CRDB resolves UPSERT on PK only,
 * persistence.md). Verdicts are deterministic, so DO NOTHING keeps the committed
 * verdict stable even if a later run reads a drifted reference snapshot.
 */
@Component
public class ManValidationLogBatchDao {

    private static final int BATCH_SIZE = 500;

    private static final String INSERT = """
            INSERT INTO man_validation_log (arrival_id, sequence, outcome, detail)
            VALUES (?,?,?,?)
            ON CONFLICT (arrival_id, sequence) DO NOTHING""";

    private final JdbcTemplate jdbc;

    public ManValidationLogBatchDao(final JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(final List<ManValidationLogEntity> verdicts) {
        if (verdicts.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT, verdicts, BATCH_SIZE, (ps, e) -> {
            ps.setObject(1, e.getArrivalId());
            ps.setInt(2, e.getSequence());
            ps.setString(3, e.getOutcome());
            ps.setString(4, e.getDetail());
        });
    }
}
