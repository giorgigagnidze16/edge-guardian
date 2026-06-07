variable "project_id" {
  type        = string
  description = "GCP project id (billing enabled)."
}

variable "region" {
  type    = string
  default = "europe-west1"
}

variable "zone" {
  type    = string
  default = "europe-west1-b"
}

variable "cluster_name" {
  type    = string
  default = "edgeguardian"
}

variable "node_machine_type" {
  type    = string
  default = "e2-medium"
}

variable "node_count" {
  type    = number
  default = 2
}

variable "github_repo" {
  type        = string
  description = "owner/repo allowed to deploy via Workload Identity Federation."
}
