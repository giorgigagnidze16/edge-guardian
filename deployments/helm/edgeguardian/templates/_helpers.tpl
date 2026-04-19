{{/*
Common labels + name helpers.
*/}}

{{- define "edgeguardian.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "edgeguardian.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "edgeguardian.labels" -}}
app.kubernetes.io/name: {{ include "edgeguardian.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: edgeguardian
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "edgeguardian.componentLabels" -}}
{{- $component := .component -}}
{{ include "edgeguardian.labels" .ctx }}
app.kubernetes.io/component: {{ $component }}
{{- end -}}

{{- define "edgeguardian.componentSelector" -}}
app.kubernetes.io/name: {{ include "edgeguardian.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "edgeguardian.externalServiceType" -}}
{{- if eq .Values.externalAccess.mode "nodePort" -}}NodePort
{{- else if eq .Values.externalAccess.mode "loadBalancer" -}}LoadBalancer
{{- else -}}ClusterIP
{{- end -}}
{{- end -}}

{{/*
EMQX may need an externally-reachable Service independently of HTTP
externalAccess.mode, since MQTT (1883/8883) cannot go through an HTTP ingress.
`.Values.emqx.external.type` overrides when set (ClusterIP|NodePort|LoadBalancer).
*/}}
{{- define "edgeguardian.emqxServiceType" -}}
{{- $t := .Values.emqx.external.type | default "" -}}
{{- if $t -}}{{ $t }}{{- else -}}{{ include "edgeguardian.externalServiceType" . }}{{- end -}}
{{- end -}}

{{/*
URL helpers. Precedence: explicit values (.Values.ui.nextAuthUrl etc) >
derive from ingress.baseDomain when ingress.enabled > empty string (signals
pki-bootstrap to fall back to node IP + NodePort).
*/}}
{{- define "edgeguardian.ingressScheme" -}}
{{- if and .Values.ingress.enabled .Values.ingress.tls.enabled -}}https{{- else -}}http{{- end -}}
{{- end -}}

{{- define "edgeguardian.nextAuthUrl" -}}
{{- if .Values.ui.nextAuthUrl -}}
{{- .Values.ui.nextAuthUrl -}}
{{- else if and .Values.ingress.enabled .Values.ingress.baseDomain -}}
{{- printf "%s://ui.%s" (include "edgeguardian.ingressScheme" .) .Values.ingress.baseDomain -}}
{{- end -}}
{{- end -}}

{{- define "edgeguardian.keycloakHostnameUrl" -}}
{{- if .Values.ui.keycloakIssuerUrl -}}
{{- .Values.ui.keycloakIssuerUrl | replace "/realms/edgeguardian" "" -}}
{{- else if and .Values.ingress.enabled .Values.ingress.baseDomain -}}
{{- printf "%s://keycloak.%s/kc" (include "edgeguardian.ingressScheme" .) .Values.ingress.baseDomain -}}
{{- end -}}
{{- end -}}

{{- define "edgeguardian.keycloakIssuerUrl" -}}
{{- if .Values.ui.keycloakIssuerUrl -}}
{{- .Values.ui.keycloakIssuerUrl -}}
{{- else -}}
{{- $h := include "edgeguardian.keycloakHostnameUrl" . -}}
{{- if $h -}}{{ printf "%s/realms/edgeguardian" $h }}{{- end -}}
{{- end -}}
{{- end -}}

{{- define "edgeguardian.controllerPublicUrl" -}}
{{- if .Values.controller.publicUrl -}}
{{- .Values.controller.publicUrl -}}
{{- else if and .Values.ingress.enabled .Values.ingress.baseDomain -}}
{{- printf "%s://controller.%s" (include "edgeguardian.ingressScheme" .) .Values.ingress.baseDomain -}}
{{- end -}}
{{- end -}}

{{- define "edgeguardian.crlUrl" -}}
{{- if .Values.controller.crlUrl -}}
{{- .Values.controller.crlUrl -}}
{{- else -}}
{{- $c := include "edgeguardian.controllerPublicUrl" . -}}
{{- if $c -}}{{ printf "%s/api/v1/pki/crl" $c }}{{- end -}}
{{- end -}}
{{- end -}}
