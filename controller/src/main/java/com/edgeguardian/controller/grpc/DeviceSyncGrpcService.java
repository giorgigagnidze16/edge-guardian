package com.edgeguardian.controller.grpc;

import com.edgeguardian.controller.grpc.proto.*;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.service.DeviceRegistry;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC implementation of DeviceSyncService.
 * Handles device registration, heartbeats, and state synchronization.
 */
public class DeviceSyncGrpcService extends DeviceSyncServiceGrpc.DeviceSyncServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(DeviceSyncGrpcService.class);

    private final DeviceRegistry registry;

    public DeviceSyncGrpcService(DeviceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void registerDevice(RegisterDeviceRequest request,
                               StreamObserver<RegisterDeviceResponse> responseObserver) {
        log.info("gRPC RegisterDevice: deviceId={}, arch={}, os={}",
                request.getDeviceId(), request.getArchitecture(), request.getOs());

        Device device = registry.register(
                request.getDeviceId(),
                request.getHostname(),
                request.getArchitecture(),
                request.getOs(),
                request.getAgentVersion()
        );

        if (request.getLabelsMap() != null && !request.getLabelsMap().isEmpty()) {
            device.getLabels().putAll(request.getLabelsMap());
        }

        // Phase 1: return a simple demo manifest
        DeviceManifest manifest = DeviceManifest.newBuilder()
                .setApiVersion("edgeguardian/v1")
                .setKind("DeviceManifest")
                .setMetadata(ManifestMetadata.newBuilder()
                        .setName(request.getDeviceId())
                        .build())
                .setSpec(ManifestSpec.newBuilder()
                        .addFiles(FileResource.newBuilder()
                                .setPath("/etc/edgeguardian/managed.conf")
                                .setContent("# Managed by EdgeGuardian\ndevice_id=" + request.getDeviceId() + "\n")
                                .setMode("0644")
                                .setOwner("root:root")
                                .build())
                        .build())
                .setVersion(1)
                .build();

        RegisterDeviceResponse response = RegisterDeviceResponse.newBuilder()
                .setAccepted(true)
                .setMessage("Device registered successfully")
                .setInitialManifest(manifest)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void heartbeat(HeartbeatRequest request,
                          StreamObserver<HeartbeatResponse> responseObserver) {
        log.debug("gRPC Heartbeat: deviceId={}", request.getDeviceId());

        registry.heartbeat(request.getDeviceId(), null);

        HeartbeatResponse response = HeartbeatResponse.newBuilder()
                .setManifestUpdated(false)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getDesiredState(GetDesiredStateRequest request,
                                StreamObserver<GetDesiredStateResponse> responseObserver) {
        log.debug("gRPC GetDesiredState: deviceId={}", request.getDeviceId());

        // Phase 1: return a basic manifest
        DeviceManifest manifest = DeviceManifest.newBuilder()
                .setApiVersion("edgeguardian/v1")
                .setKind("DeviceManifest")
                .setMetadata(ManifestMetadata.newBuilder()
                        .setName(request.getDeviceId())
                        .build())
                .setSpec(ManifestSpec.newBuilder().build())
                .setVersion(1)
                .build();

        GetDesiredStateResponse response = GetDesiredStateResponse.newBuilder()
                .setManifest(manifest)
                .setVersion(1)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void reportState(ReportStateRequest request,
                            StreamObserver<ReportStateResponse> responseObserver) {
        log.debug("gRPC ReportState: deviceId={}", request.getDeviceId());

        ReportStateResponse response = ReportStateResponse.newBuilder()
                .setAcknowledged(true)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
