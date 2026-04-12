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
