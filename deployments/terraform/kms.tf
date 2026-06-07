data "google_project" "this" {}

resource "google_kms_key_ring" "eg" {
  name       = "edgeguardian"
  location   = var.region
  depends_on = [google_project_service.enabled]
}

resource "google_kms_crypto_key" "gke_secrets" {
  name            = "gke-secrets"
  key_ring        = google_kms_key_ring.eg.id
  rotation_period = "7776000s"

  lifecycle {
    prevent_destroy = false
  }
}

resource "google_kms_crypto_key_iam_member" "gke_robot" {
  crypto_key_id = google_kms_crypto_key.gke_secrets.id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:service-${data.google_project.this.number}@container-engine-robot.iam.gserviceaccount.com"
}
