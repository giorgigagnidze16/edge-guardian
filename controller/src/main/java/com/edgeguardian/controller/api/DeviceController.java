package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.DeviceDto;
import com.edgeguardian.controller.service.DeviceRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST API for device management.
 * Provides CRUD operations over registered devices.
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceRegistry registry;

    public DeviceController(DeviceRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public Flux<DeviceDto> listDevices() {
        return Flux.fromIterable(registry.findAll())
                .map(DeviceDto::from);
    }

    @GetMapping("/{deviceId}")
    public Mono<DeviceDto> getDevice(@PathVariable String deviceId) {
        return Mono.justOrEmpty(registry.findById(deviceId))
                .map(DeviceDto::from)
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found: " + deviceId)));
    }

    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> removeDevice(@PathVariable String deviceId) {
        if (!registry.remove(deviceId)) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found: " + deviceId));
        }
        return Mono.empty();
    }

    @GetMapping("/count")
    public Mono<Integer> countDevices() {
        return Mono.just(registry.count());
    }
}
