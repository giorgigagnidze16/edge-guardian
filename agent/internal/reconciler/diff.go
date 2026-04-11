package reconciler

import "github.com/edgeguardian/agent/internal/model"

// DiffResult groups resource specs by kind for dispatch to plugins.
type DiffResult struct {
	FileSpecs    []ResourceSpec
	ServiceSpecs []ResourceSpec
	CertSpecs    []ResourceSpec
}

// Diff converts a DeviceManifest into categorized ResourceSpecs that plugins
// can process. This is the bridge between the declared YAML manifest and the
// plugin reconciliation interface.
func Diff(manifest *model.DeviceManifest) DiffResult {
	var result DiffResult

	if manifest == nil {
		return result
	}

	for _, f := range manifest.Spec.Files {
		result.FileSpecs = append(result.FileSpecs, ResourceSpec{
			Kind: "file",
			Name: f.Path,
			Fields: map[string]interface{}{
				"path":    f.Path,
				"content": f.Content,
				"mode":    f.Mode,
				"owner":   f.Owner,
			},
		})
	}

	for _, s := range manifest.Spec.Services {
		result.ServiceSpecs = append(result.ServiceSpecs, ResourceSpec{
			Kind: "service",
			Name: s.Name,
			Fields: map[string]interface{}{
				"name":    s.Name,
				"enabled": s.Enabled,
				"state":   s.State,
			},
		})
	}

	for _, c := range manifest.Spec.Certificates {
		result.CertSpecs = append(result.CertSpecs, ResourceSpec{
			Kind: "certificate",
			Name: c.Name,
			Fields: map[string]interface{}{
				"name":       c.Name,
				"commonName": c.CommonName,
				"sans":       c.SANs,
				"certPath":   c.CertPath,
				"keyPath":    c.KeyPath,
				"keyAlgo":    c.KeyAlgo,
			},
		})
	}

	return result
}

// SpecsByKind returns all specs for a given kind from the diff result.
func (d DiffResult) SpecsByKind(kind string) []ResourceSpec {
	switch kind {
	case "file":
		return d.FileSpecs
	case "service":
		return d.ServiceSpecs
	case "certificate":
		return d.CertSpecs
	default:
		return nil
	}
}

// AllSpecs returns all resource specs in a flat list.
func (d DiffResult) AllSpecs() []ResourceSpec {
	var all []ResourceSpec
	all = append(all, d.FileSpecs...)
	all = append(all, d.ServiceSpecs...)
	all = append(all, d.CertSpecs...)
	return all
}

// HasResources returns true if the diff contains any resources to reconcile.
func (d DiffResult) HasResources() bool {
	return len(d.FileSpecs) > 0 || len(d.ServiceSpecs) > 0 || len(d.CertSpecs) > 0
}
