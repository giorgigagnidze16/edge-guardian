package com.edgeguardian.controller.service.result;

import com.edgeguardian.controller.model.ApiKey;

public record ApiKeyCreateResult(ApiKey apiKey, String rawKey) {}
