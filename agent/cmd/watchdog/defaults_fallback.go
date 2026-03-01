//go:build !linux && !windows && !darwin

package main

const (
	defaultAgentBinary = "/usr/local/bin/edgeguardian-agent"
	defaultAgentConfig = "/etc/edgeguardian/agent.yaml"
	defaultDataDir     = "/var/lib/edgeguardian"
)
