package config

import "testing"

func TestValidate_RejectsUnsignedAutoUpdate(t *testing.T) {
	cfg := DefaultConfig()
	cfg.OTA.AutoUpdate = true
	cfg.OTA.SignKey = ""

	if err := cfg.Validate(); err == nil {
		t.Fatal("expected error when auto_update is on without a sign_key")
	}
}

func TestValidate_AllowsSignedAutoUpdate(t *testing.T) {
	cfg := DefaultConfig()
	cfg.OTA.AutoUpdate = true
	cfg.OTA.SignKey = "deadbeef"

	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected no error with sign_key present, got %v", err)
	}
}

func TestValidate_AllowsAutoUpdateOff(t *testing.T) {
	cfg := DefaultConfig()
	cfg.OTA.AutoUpdate = false
	cfg.OTA.SignKey = ""

	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected no error when auto_update is off, got %v", err)
	}
}
