package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.DeploymentState;
import com.edgeguardian.controller.model.OtaDeployment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OtaDeploymentRepository extends JpaRepository<OtaDeployment, Long> {

    List<OtaDeployment> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    List<OtaDeployment> findByState(DeploymentState state);
}
