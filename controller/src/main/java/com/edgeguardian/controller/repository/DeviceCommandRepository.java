package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.DeviceCommand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, Long> {

    Optional<DeviceCommand> findByCommandId(String commandId);

    List<DeviceCommand> findByDeviceIdOrderByCreatedAtDesc(String deviceId);

    List<DeviceCommand> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);
}
