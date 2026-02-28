package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for device CRUD operations.
 */
@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByDeviceId(String deviceId);

    boolean existsByDeviceId(String deviceId);

    void deleteByDeviceId(String deviceId);

    List<Device> findByState(Device.DeviceState state);
}
