const API_PREFIX = "/api/v1";

const path = (suffix: string) => `${API_PREFIX}${suffix}`;

export const endpoints = {
  prefix: API_PREFIX,

  me: () => path("/me"),

  organization: {
    get: () => path("/organization"),
    update: () => path("/organization"),
    auditLog: () => path("/organization/audit-log"),
    members: {
      list: () => path("/organization/members"),
      create: () => path("/organization/members"),
      byId: (memberId: number) => path(`/organization/members/${memberId}`),
    },
  },

  apiKeys: {
    list: () => path("/api-keys"),
    create: () => path("/api-keys"),
    byId: (keyId: number) => path(`/api-keys/${keyId}`),
  },

  enrollmentTokens: {
    list: () => path("/enrollment-tokens"),
    create: () => path("/enrollment-tokens"),
    byId: (tokenId: number) => path(`/enrollment-tokens/${tokenId}`),
  },

  devices: {
    list: () => path("/devices"),
    count: () => path("/devices/count"),
    byId: (deviceId: string) => path(`/devices/${deviceId}`),
    manifest: (deviceId: string) => path(`/devices/${deviceId}/manifest`),
    labels: (deviceId: string) => path(`/devices/${deviceId}/labels`),
    logs: (deviceId: string) => path(`/devices/${deviceId}/logs`),
    telemetry: (deviceId: string) => path(`/devices/${deviceId}/telemetry`),
    telemetryHourly: (deviceId: string) => path(`/devices/${deviceId}/telemetry/hourly`),
  },

  certificates: {
    list: () => path("/certificates"),
    ca: () => path("/certificates/ca"),
    revoke: (certId: number) => path(`/certificates/${certId}/revoke`),
    requests: {
      list: () => path("/certificates/requests"),
      approve: (requestId: number) => path(`/certificates/requests/${requestId}/approve`),
      reject: (requestId: number) => path(`/certificates/requests/${requestId}/reject`),
    },
  },

  ota: {
    artifacts: {
      list: () => path("/ota/artifacts"),
      create: () => path("/ota/artifacts"),
      byId: (artifactId: number) => path(`/ota/artifacts/${artifactId}`),
    },
    deployments: {
      list: () => path("/ota/deployments"),
      create: () => path("/ota/deployments"),
      byId: (deploymentId: number) => path(`/ota/deployments/${deploymentId}`),
      devices: (deploymentId: number) =>
        path(`/ota/deployments/${deploymentId}/devices`),
    },
  },

  agent: {
    installer: () => path("/agent/installer"),
  },
} as const;
