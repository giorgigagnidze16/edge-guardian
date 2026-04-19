//go:build windows

package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"

	"github.com/edgeguardian/agent/internal/config"
	"go.uber.org/zap"
	"golang.org/x/sys/windows/svc"
)

const windowsServiceName = "EdgeGuardianAgent"

// runPlatform runs the agent either as a Windows service (when launched by
// the Service Control Manager) or as an interactive console process. The
// detection is handled by the SCM dispatcher itself via svc.IsWindowsService.
func runPlatform(cfg *config.Config, logger *zap.Logger) error {
	isService, err := svc.IsWindowsService()
	if err != nil {
		return fmt.Errorf("detect service mode: %w", err)
	}
	if isService {
		return svc.Run(windowsServiceName, &serviceHandler{cfg: cfg, logger: logger})
	}
	return runInteractive(cfg, logger)
}

func runInteractive(cfg *config.Config, logger *zap.Logger) error {
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

// serviceHandler adapts runAgent to the Windows SCM protocol: it reports
// state transitions (StartPending -> Running -> StopPending -> Stopped) and
// translates Stop/Shutdown control requests into context cancellation.
type serviceHandler struct {
	cfg    *config.Config
	logger *zap.Logger
}

func (s *serviceHandler) Execute(_ []string, req <-chan svc.ChangeRequest, status chan<- svc.Status) (bool, uint32) {
	const accepted = svc.AcceptStop | svc.AcceptShutdown
	status <- svc.Status{State: svc.StartPending}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	errCh := make(chan error, 1)
	go func() { errCh <- runAgent(ctx, s.cfg, s.logger) }()

	status <- svc.Status{State: svc.Running, Accepts: accepted}

	for {
		select {
		case c := <-req:
			switch c.Cmd {
			case svc.Interrogate:
				status <- c.CurrentStatus
			case svc.Stop, svc.Shutdown:
				status <- svc.Status{State: svc.StopPending}
				cancel()
				if err := <-errCh; err != nil {
					s.logger.Error("agent returned error on shutdown", zap.Error(err))
					status <- svc.Status{State: svc.Stopped, Win32ExitCode: 1}
					return false, 1
				}
				status <- svc.Status{State: svc.Stopped}
				return false, 0
			default:
				s.logger.Warn("unexpected SCM control request", zap.Uint32("cmd", uint32(c.Cmd)))
			}
		case err := <-errCh:
			if err != nil {
				s.logger.Error("agent exited with error", zap.Error(err))
				status <- svc.Status{State: svc.Stopped, Win32ExitCode: 1}
				return false, 1
			}
			status <- svc.Status{State: svc.Stopped}
			return false, 0
		}
	}
}
