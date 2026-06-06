package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.CreateShellSessionRequest;
import com.edgeguardian.controller.dto.ShellSessionResponse;
import com.edgeguardian.controller.security.TenantPrincipal;
import com.edgeguardian.controller.service.ShellSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opens interactive shell sessions. The terminal I/O itself runs over the
 * WebSocket registered in {@code ShellWebSocketConfig}; this endpoint performs
 * authorization and mints the one-time WebSocket ticket.
 */
@RestController
@RequestMapping(ApiPaths.DEVICES_BASE)
@RequiredArgsConstructor
public class ShellSessionController {

    private static final int DEFAULT_ROWS = 24;
    private static final int DEFAULT_COLS = 80;

    private final ShellSessionService shellSessionService;

    @PostMapping("/{deviceId}/shell/sessions")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'OPERATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ShellSessionResponse create(@PathVariable String deviceId,
                                       @RequestBody(required = false) CreateShellSessionRequest request,
                                       @AuthenticationPrincipal TenantPrincipal principal) {
        int rows = request != null && request.rows() != null ? request.rows() : DEFAULT_ROWS;
        int cols = request != null && request.cols() != null ? request.cols() : DEFAULT_COLS;
        var ticket = shellSessionService.create(principal, deviceId, rows, cols);
        return new ShellSessionResponse(ticket.sessionId(), ticket.ticket());
    }
}
