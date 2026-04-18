package com.edgeguardian.controller.api;

import com.edgeguardian.controller.service.AgentInstallerService;
import com.edgeguardian.controller.service.AgentInstallerService.Os;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentInstallerController {

    private final AgentInstallerService installers;

    @GetMapping("/installer")
    public ResponseEntity<String> installer(@RequestParam String os,
                                            @RequestParam Long tokenId) throws IOException {
        Os target = Os.of(os);
        String body = installers.renderInstaller(target, tokenId);
        String filename = target == Os.WINDOWS ? "install.ps1" : "install.sh";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .body(body);
    }

    @GetMapping("/binary")
    public ResponseEntity<InputStreamResource> binary(@RequestParam String os,
                                                      @RequestParam(defaultValue = "amd64") String arch)
            throws IOException {
        Os target = Os.of(os);
        InputStream stream = installers.openBinary(target, arch);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + target.binaryName)
                .body(new InputStreamResource(stream));
    }
}
