CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE device_telemetry (
    time                TIMESTAMPTZ NOT NULL,
    device_id           VARCHAR(255) NOT NULL,
    organization_id     BIGINT,
    cpu_usage_percent   DOUBLE PRECISION DEFAULT 0,
    memory_used_bytes   BIGINT           DEFAULT 0,
    memory_total_bytes  BIGINT           DEFAULT 0,
    disk_used_bytes     BIGINT           DEFAULT 0,
    disk_total_bytes    BIGINT           DEFAULT 0,
    temperature_celsius DOUBLE PRECISION DEFAULT 0,
    uptime_seconds      BIGINT           DEFAULT 0,
    last_reconcile      TIMESTAMPTZ,
    reconcile_status    VARCHAR(32) DEFAULT 'converged'
);

SELECT create_hypertable('device_telemetry', by_range('time', INTERVAL '1 day'));

CREATE INDEX idx_telemetry_device_time ON device_telemetry (device_id, time DESC);
CREATE INDEX idx_telemetry_org_time    ON device_telemetry (organization_id, time DESC);

CREATE MATERIALIZED VIEW device_telemetry_hourly
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', time) AS bucket,
    device_id,
    AVG(cpu_usage_percent)   AS avg_cpu,
    MAX(cpu_usage_percent)   AS max_cpu,
    AVG(memory_used_bytes)   AS avg_memory,
    MAX(memory_used_bytes)   AS max_memory,
    AVG(memory_total_bytes)  AS avg_memory_total,
    AVG(disk_used_bytes)     AS avg_disk,
    MAX(disk_used_bytes)     AS max_disk,
    AVG(disk_total_bytes)    AS avg_disk_total,
    AVG(temperature_celsius) AS avg_temperature,
    MAX(temperature_celsius) AS max_temperature,
    MAX(uptime_seconds)      AS max_uptime,
    COUNT(*)                 AS sample_count
FROM device_telemetry
GROUP BY bucket, device_id
WITH NO DATA;

SELECT add_continuous_aggregate_policy('device_telemetry_hourly',
    start_offset      => INTERVAL '3 hours',
    end_offset        => INTERVAL '10 minutes',
    schedule_interval => INTERVAL '30 minutes');

ALTER TABLE device_telemetry SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id',
    timescaledb.compress_orderby   = 'time DESC'
);

SELECT add_compression_policy('device_telemetry', INTERVAL '7 days');
SELECT add_retention_policy  ('device_telemetry', INTERVAL '30 days');
