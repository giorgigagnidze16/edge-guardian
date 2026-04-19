//go:build !windows

package main

import (
	"context"
	"os"
	"os/signal"

	"github.com/edgeguardian/agent/internal/config"
	"go.uber.org/zap"
)

func runPlatform(cfg *config.Config, logger *zap.Logger) error {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, platformSignals()...)
	go func() {
		sig := <-sigCh
		logger.Info("received signal, shutting down", zap.String("signal", sig.String()))
		cancel()
	}()

	return runAgent(ctx, cfg, logger)
}
