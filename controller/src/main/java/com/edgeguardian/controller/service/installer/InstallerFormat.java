package com.edgeguardian.controller.service.installer;

import java.util.Arrays;

/**
 * Supported installer file formats and their OS compatibility. A format is
 * bound to exactly one {@link Os}; requesting a format on the wrong OS is
 * rejected by {@link #validateFor(Os)}.
 */
public enum InstallerFormat {
    SHELL("install.sh", "installers/install.sh.tmpl", Os.LINUX),
    PS1("install.ps1", "installers/install.ps1.tmpl", Os.WINDOWS),
    CMD("EdgeGuardianInstall.cmd", "installers/install.cmd.tmpl", Os.WINDOWS);

    public final String filename;
    public final String templatePath;
    public final Os os;

    InstallerFormat(String filename, String templatePath, Os os) {
        this.filename = filename;
        this.templatePath = templatePath;
        this.os = os;
    }

    public static InstallerFormat defaultFor(Os os) {
        return switch (os) {
            case LINUX -> SHELL;
            case WINDOWS -> CMD;
        };
    }

    public static InstallerFormat resolve(Os os, String name) {
        if (name == null || name.isBlank()) {
            return defaultFor(os);
        }
        InstallerFormat fmt = Arrays.stream(values())
            .filter(f -> f.name().equalsIgnoreCase(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown installer format: " + name));
        fmt.validateFor(os);
        return fmt;
    }

    public void validateFor(Os os) {
        if (this.os != os) {
            throw new IllegalArgumentException(
                "Installer format " + this + " is not supported for os " + os.slug);
        }
    }
}
