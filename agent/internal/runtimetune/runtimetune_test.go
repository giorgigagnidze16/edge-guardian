package runtimetune

import (
	"math"
	"runtime"
	"runtime/debug"
	"testing"
)

const (
	mib = 1 << 20
	gib = 1 << 30
)

func TestCompute(t *testing.T) {
	tests := []struct {
		name        string
		numCPU      int
		totalRAM    int64
		envProcs    string
		envMemLimit string
		wantProcs   int
		wantSetMem  bool
		wantMemMiB  int64
	}{
		{
			name:       "multicore idle device derives both",
			numCPU:     8,
			totalRAM:   4 * gib,
			wantProcs:  2,
			wantSetMem: true,
			wantMemMiB: 256,
		},
		{
			name:       "single core leaves GOMAXPROCS untouched",
			numCPU:     1,
			totalRAM:   512 * mib,
			wantProcs:  0,
			wantSetMem: true,
			wantMemMiB: 256,
		},
		{
			name:       "two cores already at cap leaves GOMAXPROCS untouched",
			numCPU:     2,
			totalRAM:   2 * gib,
			wantProcs:  0,
			wantSetMem: true,
			wantMemMiB: 256,
		},
		{
			name:       "operator GOMAXPROCS env wins",
			numCPU:     8,
			totalRAM:   4 * gib,
			envProcs:   "4",
			wantProcs:  0,
			wantSetMem: true,
			wantMemMiB: 256,
		},
		{
			name:        "operator GOMEMLIMIT env wins",
			numCPU:      8,
			totalRAM:    4 * gib,
			envMemLimit: "64MiB",
			wantProcs:   2,
			wantSetMem:  false,
		},
		{
			name:       "unknown RAM skips memory limit",
			numCPU:     4,
			totalRAM:   0,
			wantProcs:  2,
			wantSetMem: false,
		},
		{
			name:       "small RAM derives below cap",
			numCPU:     4,
			totalRAM:   128 * mib,
			wantProcs:  2,
			wantSetMem: true,
			wantMemMiB: 64,
		},
		{
			name:       "tiny RAM floors the limit",
			numCPU:     1,
			totalRAM:   40 * mib,
			wantProcs:  0,
			wantSetMem: true,
			wantMemMiB: 32,
		},
		{
			name:       "huge RAM caps the limit",
			numCPU:     16,
			totalRAM:   16 * gib,
			wantProcs:  2,
			wantSetMem: true,
			wantMemMiB: 256,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := Compute(tt.numCPU, tt.totalRAM, tt.envProcs, tt.envMemLimit)
			if got.GOMAXPROCS != tt.wantProcs {
				t.Errorf("GOMAXPROCS = %d, want %d", got.GOMAXPROCS, tt.wantProcs)
			}
			if got.SetMemLimit != tt.wantSetMem {
				t.Errorf("SetMemLimit = %v, want %v", got.SetMemLimit, tt.wantSetMem)
			}
			if tt.wantSetMem && got.MemLimitBytes != tt.wantMemMiB*mib {
				t.Errorf("MemLimitBytes = %d MiB, want %d MiB",
					got.MemLimitBytes/mib, tt.wantMemMiB)
			}
		})
	}
}

func TestApplySetsRuntimeLimits(t *testing.T) {
	origProcs := runtime.GOMAXPROCS(-1)
	origMem := debug.SetMemoryLimit(-1)
	t.Cleanup(func() {
		runtime.GOMAXPROCS(origProcs)
		debug.SetMemoryLimit(origMem)
	})

	Apply(Limits{GOMAXPROCS: 1, SetMemLimit: true, MemLimitBytes: 64 * mib})

	if got := runtime.GOMAXPROCS(-1); got != 1 {
		t.Errorf("GOMAXPROCS = %d, want 1", got)
	}
	if got := debug.SetMemoryLimit(-1); got != 64*mib {
		t.Errorf("memory limit = %d, want %d", got, 64*mib)
	}
}

func TestApplyNoopLeavesRuntimeUntouched(t *testing.T) {
	runtime.GOMAXPROCS(3)
	debug.SetMemoryLimit(math.MaxInt64)
	t.Cleanup(func() { runtime.GOMAXPROCS(runtime.NumCPU()) })

	Apply(Limits{GOMAXPROCS: 0, SetMemLimit: false})

	if got := runtime.GOMAXPROCS(-1); got != 3 {
		t.Errorf("GOMAXPROCS = %d, want 3 (untouched)", got)
	}
	if got := debug.SetMemoryLimit(-1); got != math.MaxInt64 {
		t.Errorf("memory limit = %d, want untouched", got)
	}
}
