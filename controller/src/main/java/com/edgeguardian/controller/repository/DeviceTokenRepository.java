package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    Optional<DeviceToken> findByTokenHash(String tokenHash);

    Optional<DeviceToken> findByDeviceId(String deviceId);

    void deleteByDeviceId(String deviceId);
}
