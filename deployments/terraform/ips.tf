resource "google_compute_address" "ingress" {
  name       = "edgeguardian-ingress"
  region     = var.region
  depends_on = [google_project_service.enabled]
}

resource "google_compute_address" "mqtt" {
  name       = "edgeguardian-mqtt"
  region     = var.region
  depends_on = [google_project_service.enabled]
}
