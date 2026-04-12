package com.edgeguardian.controller.service.result;

import com.edgeguardian.controller.model.Device;

/**
 * Result of a successful device enrollment.
 */
public record EnrollmentResult(Device device, String deviceToken) {}
