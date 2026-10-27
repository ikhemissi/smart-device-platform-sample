package db.migration;

import io.github.ikhemissi.sdp.codecs.Metric;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Repeatable migration seeding {@code sdp.metric_definitions} from the codec library's metric catalogue, the
 * single source of truth for canonical metric names and units (ADR-0003). It re-runs whenever the catalogue
 * changes because the checksum is derived from it.
 */
public class R__Metric_definitions extends BaseJavaMigration {

    @Override
    public Integer getChecksum() {
        CRC32 crc = new CRC32();
        for (Metric metric : Metric.values()) {
            crc.update((metric.key() + '|' + metric.unit() + '|' + metric.description() + '\n').getBytes(StandardCharsets.UTF_8));
        }
        return (int) crc.getValue();
    }

    @Override
    public void migrate(Context context) throws Exception {
        String sql = """
                INSERT INTO sdp.metric_definitions (name, unit, description) VALUES (?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET unit = EXCLUDED.unit, description = EXCLUDED.description
                """;
        try (PreparedStatement statement = context.getConnection().prepareStatement(sql)) {
            for (Metric metric : Metric.values()) {
                statement.setString(1, metric.key());
                statement.setString(2, metric.unit());
                statement.setString(3, metric.description());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
