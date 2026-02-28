package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.DeviceManifestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for device manifest CRUD operations.
 */
@Repository
public interface DeviceManifestRepository extends JpaRepository<DeviceManifestEntity, Long> {

    Optional<DeviceManifestEntity> findByDeviceId(String deviceId);

    boolean existsByDeviceId(String deviceId);

    void deleteByDeviceId(String deviceId);
}
