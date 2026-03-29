package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.CommandExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommandExecutionRepository extends JpaRepository<CommandExecution, Long> {

    List<CommandExecution> findByCommandIdOrderByReceivedAtAsc(String commandId);

    List<CommandExecution> findByDeviceIdOrderByReceivedAtDesc(String deviceId);
}
