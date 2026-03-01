//go:build !linux && !windows && !darwin && !android

package main

const (
	defaultAgentBinary = "/usr/local/bin/edgeguardian-agent"
	defaultAgentConfig = "/etc/edgeguardian/agent.yaml"
	defaultDataDir     = "/var/lib/edgeguardian"
)
