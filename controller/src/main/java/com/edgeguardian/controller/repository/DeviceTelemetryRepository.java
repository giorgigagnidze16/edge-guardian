package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.DeviceTelemetry;
import com.edgeguardian.controller.model.DeviceTelemetryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceTelemetryRepository extends JpaRepository<DeviceTelemetry, DeviceTelemetryId> {

    /**
     * Latest telemetry row for a single device.
     */
    @Query(value = "SELECT * FROM device_telemetry WHERE device_id = :deviceId ORDER BY time DESC LIMIT 1",
            nativeQuery = true)
    Optional<DeviceTelemetry> findLatestByDeviceId(@Param("deviceId") String deviceId);

    /**
     * Latest telemetry row per device (for fleet overview).
     */
    @Query(value = "SELECT DISTINCT ON (device_id) * FROM device_telemetry ORDER BY device_id, time DESC",
            nativeQuery = true)
    List<DeviceTelemetry> findLatestForAllDevices();

    /**
     * Raw telemetry rows in a time range for a device.
     */
    @Query(value = "SELECT * FROM device_telemetry WHERE device_id = :deviceId AND time >= :start AND time <= :end ORDER BY time ASC",
            nativeQuery = true)
    List<DeviceTelemetry> findByDeviceIdAndTimeRange(
            @Param("deviceId") String deviceId,
            @Param("start") Instant start,
            @Param("end") Instant end);

    /**
     * Hourly aggregates from the continuous aggregate view.
     * Returns results mapped to DeviceTelemetry (avg values).
     */
    @Query(value = """
            SELECT bucket AS time, device_id, NULL AS organization_id,
                   avg_cpu AS cpu_usage_percent,
                   avg_memory AS memory_used_bytes,
                   avg_memory_total AS memory_total_bytes,
                   avg_disk AS disk_used_bytes,
                   avg_disk_total AS disk_total_bytes,
                   avg_temperature AS temperature_celsius,
                   max_uptime AS uptime_seconds,
                   NULL AS last_reconcile,
                   NULL AS reconcile_status
            FROM device_telemetry_hourly
            WHERE device_id = :deviceId AND bucket >= :start AND bucket <= :end
            ORDER BY bucket ASC
            """, nativeQuery = true)
    List<DeviceTelemetry> findHourlyByDeviceIdAndTimeRange(
            @Param("deviceId") String deviceId,
            @Param("start") Instant start,
            @Param("end") Instant end);
}
