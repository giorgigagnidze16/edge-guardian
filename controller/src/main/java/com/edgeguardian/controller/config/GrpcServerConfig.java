package com.edgeguardian.controller.config;

import com.edgeguardian.controller.grpc.DeviceSyncGrpcService;
import com.edgeguardian.controller.service.DeviceRegistry;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Configures and manages the gRPC server lifecycle.
 * Phase 1: plain-text gRPC. Phase 3: mTLS-secured.
 */
@Configuration
public class GrpcServerConfig {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfig.class);

    @Value("${edgeguardian.controller.grpc-port:9090}")
    private int grpcPort;

    private final DeviceRegistry deviceRegistry;
    private Server grpcServer;

    public GrpcServerConfig(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    @PostConstruct
    public void startGrpcServer() throws IOException {
        grpcServer = ServerBuilder.forPort(grpcPort)
                .addService(new DeviceSyncGrpcService(deviceRegistry))
                .build()
                .start();

        log.info("gRPC server started on port {}", grpcPort);

        // Shutdown hook for clean exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down gRPC server");
            if (grpcServer != null) {
                grpcServer.shutdown();
            }
        }));
    }

    @PreDestroy
    public void stopGrpcServer() {
        if (grpcServer != null) {
            grpcServer.shutdown();
            log.info("gRPC server stopped");
        }
    }
}
