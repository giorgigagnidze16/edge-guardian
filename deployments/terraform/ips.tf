resource "google_compute_address" "ingress" {
  name       = "edgeguardian-ingress"
  region     = var.region
  depends_on = [google_project_service.enabled]
}
