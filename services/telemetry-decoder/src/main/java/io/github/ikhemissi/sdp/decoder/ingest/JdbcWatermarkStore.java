package io.github.ikhemissi.sdp.decoder.ingest;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWatermarkStore implements WatermarkStore {

    private final JdbcTemplate jdbc;

    public JdbcWatermarkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void register(String partitionId) {
        jdbc.update("""
                INSERT INTO sdp.ingestion_watermarks (partition_id, watermark) VALUES (?, '-infinity')
                ON CONFLICT (partition_id) DO NOTHING""", partitionId);
    }

    @Override
    public void advance(String partitionId, Instant watermark) {
        jdbc.update("""
                INSERT INTO sdp.ingestion_watermarks (partition_id, watermark, updated_at) VALUES (?, ?, now())
                ON CONFLICT (partition_id) DO UPDATE
                   SET watermark = GREATEST(sdp.ingestion_watermarks.watermark, EXCLUDED.watermark),
                       updated_at = now()""", partitionId, Timestamp.from(watermark));
    }
}
