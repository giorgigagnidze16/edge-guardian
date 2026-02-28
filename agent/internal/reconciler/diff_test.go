package reconciler

import (
	"testing"

	"github.com/edgeguardian/agent/internal/model"
)

func TestDiff_NilManifest(t *testing.T) {
	result := Diff(nil)
	if result.HasResources() {
		t.Fatal("expected no resources from nil manifest")
	}
}

func TestDiff_EmptySpec(t *testing.T) {
	m := &model.DeviceManifest{
		APIVersion: "edgeguardian/v1",
		Kind:       "DeviceManifest",
	}
	result := Diff(m)
	if result.HasResources() {
		t.Fatal("expected no resources from empty spec")
	}
}

func TestDiff_Files(t *testing.T) {
	m := &model.DeviceManifest{
		Spec: model.ManifestSpec{
			Files: []model.FileResource{
				{Path: "/etc/foo.conf", Content: "bar", Mode: "0644", Owner: "root:root"},
				{Path: "/tmp/test.txt", Content: "hello", Mode: "0600", Owner: "user:user"},
			},
		},
	}

	result := Diff(m)
	if len(result.FileSpecs) != 2 {
		t.Fatalf("expected 2 file specs, got %d", len(result.FileSpecs))
	}
	if result.FileSpecs[0].Name != "/etc/foo.conf" {
		t.Fatalf("expected path '/etc/foo.conf', got %q", result.FileSpecs[0].Name)
	}
	if result.FileSpecs[0].Kind != "file" {
		t.Fatalf("expected kind 'file', got %q", result.FileSpecs[0].Kind)
	}
	if result.FileSpecs[0].Fields["content"] != "bar" {
		t.Fatalf("expected content 'bar', got %v", result.FileSpecs[0].Fields["content"])
	}
}

func TestDiff_Services(t *testing.T) {
	m := &model.DeviceManifest{
		Spec: model.ManifestSpec{
			Services: []model.ServiceResource{
				{Name: "nginx", Enabled: "true", State: "running"},
			},
		},
	}

	result := Diff(m)
	if len(result.ServiceSpecs) != 1 {
		t.Fatalf("expected 1 service spec, got %d", len(result.ServiceSpecs))
	}
	if result.ServiceSpecs[0].Name != "nginx" {
		t.Fatalf("expected name 'nginx', got %q", result.ServiceSpecs[0].Name)
	}
	if result.ServiceSpecs[0].Fields["state"] != "running" {
		t.Fatalf("expected state 'running', got %v", result.ServiceSpecs[0].Fields["state"])
	}
}

func TestDiff_SpecsByKind(t *testing.T) {
	m := &model.DeviceManifest{
		Spec: model.ManifestSpec{
			Files:    []model.FileResource{{Path: "/a"}},
			Services: []model.ServiceResource{{Name: "sshd"}},
		},
	}
	result := Diff(m)

	if len(result.SpecsByKind("file")) != 1 {
		t.Fatal("expected 1 file spec from SpecsByKind")
	}
	if len(result.SpecsByKind("service")) != 1 {
		t.Fatal("expected 1 service spec from SpecsByKind")
	}
	if len(result.SpecsByKind("unknown")) != 0 {
		t.Fatal("expected 0 specs for unknown kind")
	}
}

func TestDiff_AllSpecs(t *testing.T) {
	m := &model.DeviceManifest{
		Spec: model.ManifestSpec{
			Files:    []model.FileResource{{Path: "/a"}, {Path: "/b"}},
			Services: []model.ServiceResource{{Name: "sshd"}},
		},
	}
	result := Diff(m)
	all := result.AllSpecs()
	if len(all) != 3 {
		t.Fatalf("expected 3 total specs, got %d", len(all))
	}
}
