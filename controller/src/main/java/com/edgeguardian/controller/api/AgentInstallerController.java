package com.edgeguardian.controller.api;

import com.edgeguardian.controller.service.AgentInstallerService;
import com.edgeguardian.controller.service.installer.InstallerFormat;
import com.edgeguardian.controller.service.installer.Os;
import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.AGENT_BASE)
@RequiredArgsConstructor
public class AgentInstallerController {

    private final AgentInstallerService installers;

    @GetMapping(ApiPaths.AGENT_INSTALLER_PATH)
    public ResponseEntity<String> installer(@RequestParam String os,
                                            @RequestParam String token,
                                            @RequestParam(required = false) String format,
                                            @RequestParam(defaultValue = "amd64") String arch) throws IOException {
        Os target = Os.of(os);
        InstallerFormat fmt = InstallerFormat.resolve(target, format);
        String body = installers.renderInstaller(target, fmt, token, arch);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fmt.filename)
            .body(body);
    }

    @GetMapping(ApiPaths.AGENT_BINARY_PATH)
    public ResponseEntity<InputStreamResource> binary(@RequestParam String os,
                                                      @RequestParam(defaultValue = "amd64") String arch) {
        Os target = Os.of(os);
        InputStream stream = installers.openBinary(target, arch);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + target.binaryName)
            .body(new InputStreamResource(stream));
    }
}
