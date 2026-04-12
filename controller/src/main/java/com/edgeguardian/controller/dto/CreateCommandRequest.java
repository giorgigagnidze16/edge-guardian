package com.edgeguardian.controller.dto;

import java.util.Map;

public record CreateCommandRequest(
        String type,
        Map<String, String> params,
        Map<String, Object> script,
        Map<String, Object> hooks,
        int timeoutSeconds
) {}
