package runtimetune

import (
	"runtime"
	"runtime/debug"
)

const (
	procsCap = 2
	memRatio = 2
	memFloor = 32 << 20
	memCap   = 256 << 20
)

type Limits struct {
	GOMAXPROCS    int
	SetMemLimit   bool
	MemLimitBytes int64
}

func Compute(numCPU int, totalRAMBytes int64, envProcs, envMemLimit string) Limits {
	var l Limits

	if envProcs == "" && numCPU > procsCap {
		l.GOMAXPROCS = procsCap
	}

	if envMemLimit == "" && totalRAMBytes > 0 {
		limit := totalRAMBytes / memRatio
		if limit < memFloor {
			limit = memFloor
		}
		if limit > memCap {
			limit = memCap
		}
		l.SetMemLimit = true
		l.MemLimitBytes = limit
	}

	return l
}

func Apply(l Limits) {
	if l.GOMAXPROCS > 0 {
		runtime.GOMAXPROCS(l.GOMAXPROCS)
	}
	if l.SetMemLimit {
		debug.SetMemoryLimit(l.MemLimitBytes)
	}
}
