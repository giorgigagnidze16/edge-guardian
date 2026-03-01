package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.DeploymentDeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeploymentDeviceStatusRepository extends JpaRepository<DeploymentDeviceStatus, Long> {

    List<DeploymentDeviceStatus> findByDeploymentId(Long deploymentId);

    Optional<DeploymentDeviceStatus> findByDeploymentIdAndDeviceId(Long deploymentId, String deviceId);
}
