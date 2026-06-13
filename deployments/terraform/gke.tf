resource "google_container_cluster" "primary" {
  name                     = var.cluster_name
  location                 = var.zone
  remove_default_node_pool = true
  initial_node_count       = 1
  deletion_protection      = false
  networking_mode          = "VPC_NATIVE"
  ip_allocation_policy {}

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  database_encryption {
    state    = "ENCRYPTED"
    key_name = google_kms_crypto_key.gke_secrets.id
  }

  depends_on = [
    google_project_service.enabled,
    google_kms_crypto_key_iam_member.gke_robot,
  ]
}

resource "google_container_node_pool" "primary" {
  name       = "primary"
  cluster    = google_container_cluster.primary.id
  node_count = var.node_count

  node_config {
    machine_type = var.node_machine_type
    disk_size_gb = 50
    oauth_scopes = ["https://www.googleapis.com/auth/cloud-platform"]
    workload_metadata_config {
      mode = "GKE_METADATA"
    }
  }
}
