package com.edgeguardian.controller.service.installer;

/**
 * Target operating system for an agent installer. The slug is the OS
 * identifier used in storage object keys and query strings; binaryName is the
 * on-disk filename the installer drops into place.
 */
public enum Os {
    LINUX("linux", "edgeguardian-agent"),
    WINDOWS("windows", "edgeguardian-agent.exe");

    public final String slug;
    public final String binaryName;

    Os(String slug, String binaryName) {
        this.slug = slug;
        this.binaryName = binaryName;
    }

    public static Os of(String slug) {
        for (Os v : values()) {
            if (v.slug.equalsIgnoreCase(slug)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unsupported os: " + slug);
    }
}
