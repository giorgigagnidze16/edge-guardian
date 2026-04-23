package com.edgeguardian.controller.api;

/**
 * Single source of truth for every HTTP API path in the controller. Controllers
 * reference the {@code _BASE}/{@code _PATH} pairs in their mapping annotations;
 * security rules and outbound URL builders reference the composed full-path
 * constants. Changing a path means changing exactly one line here.
 */
public final class ApiPaths {

    private ApiPaths() {}

    // --- Root ---------------------------------------------------------------

    public static final String API_V1          = "/api/v1";
    public static final String API_V1_PATTERN  = API_V1 + "/**";

    // --- Agent (AgentInstallerController) -----------------------------------

    public static final String AGENT_BASE           = API_V1 + "/agent";
    public static final String AGENT_PREFIX         = AGENT_BASE + "/";

    public static final String AGENT_INSTALLER_PATH = "/installer";
    public static final String AGENT_BINARY_PATH    = "/binary";
    public static final String AGENT_ENROLL_PATH    = "/enroll";

    public static final String AGENT_INSTALLER     = AGENT_BASE + AGENT_INSTALLER_PATH;
    public static final String AGENT_BINARY        = AGENT_BASE + AGENT_BINARY_PATH;
    public static final String AGENT_ENROLL        = AGENT_BASE + AGENT_ENROLL_PATH;

    // --- PKI (PkiController) ------------------------------------------------

    public static final String PKI_BASE             = API_V1 + "/pki";

    public static final String PKI_CRL_FILE_PATH    = "/crl/{orgId}.crl";
    public static final String PKI_CRL_BASE         = PKI_BASE + "/crl";
    public static final String PKI_CRL_PATTERN      = PKI_CRL_BASE + "/**";

    public static final String PKI_CA_BUNDLE_PATH   = "/ca-bundle";
    public static final String PKI_CA_BUNDLE        = PKI_BASE + PKI_CA_BUNDLE_PATH;

    public static final String PKI_BROKER_CA_PATH   = "/broker-ca";
    public static final String PKI_BROKER_CA        = PKI_BASE + PKI_BROKER_CA_PATH;

    // --- Other controllers (paths declared only on @RequestMapping) ---------

    public static final String DEVICES_BASE           = API_V1 + "/devices";
    public static final String TELEMETRY_BASE        = DEVICES_BASE + "/{deviceId}/telemetry";
    public static final String OTA_BASE               = API_V1 + "/ota";
    public static final String ORGANIZATION_BASE     = API_V1 + "/organization";
    public static final String ME_BASE                = API_V1 + "/me";
    public static final String CERTIFICATES_BASE     = API_V1 + "/certificates";
    public static final String ENROLLMENT_TOKENS_BASE = API_V1 + "/enrollment-tokens";
    public static final String API_KEYS_BASE         = API_V1 + "/api-keys";

    /**
     * Dev-only fallback used when {@code edgeguardian.controller.pki.crl-distribution-base-url}
     * is not configured. The hostname is intentionally local; production deployments must
     * override via config so issued certificates carry a reachable CDP.
     */
    public static final String DEFAULT_CRL_DISTRIBUTION_BASE_URL = "http://localhost:8443" + PKI_CRL_BASE;
}
