//go:build linux && !android

package main

const (
	defaultAgentBinary = "/usr/local/bin/edgeguardian-agent"
	defaultAgentConfig = "/etc/edgeguardian/agent.yaml"
	defaultDataDir     = "/var/lib/edgeguardian"
)
