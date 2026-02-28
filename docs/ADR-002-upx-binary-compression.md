# ADR-002: UPX Binary Compression for the Go Agent

## Context

The EdgeGuardian Go agent targets resource-constrained edge devices (Raspberry Pi 3/4/5, similar ARM SBCs) where storage, bandwidth, and OTA update speed matter. The project specification defines a hard constraint:

> Agent binary size (linux/arm64, stripped) < 5 MB

After replacing gRPC with HTTP/JSON (see ADR-001), the agent binary is **7.2 MB** on linux/arm64 with standard Go build flags (`-ldflags="-s -w"`, `CGO_ENABLED=0`). This exceeds the target by 44%.

### Why is a Go binary so large?

Go compiles to statically-linked binaries — the entire Go runtime, garbage collector, goroutine scheduler, and all imported standard library code is embedded in every binary. Unlike C/C++ programs that dynamically link to `libc`, a Go binary is fully self-contained. This is a deliberate design choice by Go's authors: it simplifies deployment (single file, no dependency hell) at the cost of larger binaries.

The primary size contributors in the EdgeGuardian agent (measured via `go tool nm`):

| Package | Cause | Notes |
|---|---|---|
| `crypto/tls` + `crypto/internal/fips140` | TLS 1.3 implementation with FIPS 140-3 validated cryptography | Required by both `net/http` and MQTT; includes elliptic curve implementations, X.509 parsing, certificate verification |
| `net/http` | Full HTTP/1.1 client and server implementation | Go links the entire `net/http` package even when only the client is used; includes HTTP/2, cookie handling, multipart parsing |
| `net` | DNS resolver, TCP/UDP stack, Unix socket support | Pulled by `net/http` and MQTT |
| `encoding/json` | Reflection-based JSON marshaling/unmarshaling | Used for all agent-controller communication |
| `go.uber.org/zap` | Structured logging library | ~300 KB; heavier than `log/slog` but provides better performance for high-throughput logging |
| `go.etcd.io/bbolt` | BoltDB embedded database | ~200 KB |
| `github.com/eclipse/paho.mqtt.golang` | Eclipse Paho MQTT v3.1.1 client | Pulls in `gorilla/websocket` which adds HTTP server-side code |

Go's linker does perform dead code elimination, but it operates at the function level, not the instruction level. If any function in a package is reachable, all types and interfaces in that package may be retained due to Go's reflection and interface dispatch mechanisms.

### Standard Go binary size reduction techniques

| Technique | Savings | Applied? |
|---|---|---|
| `-ldflags="-s"` (strip symbol table) | ~15-20% | Yes |
| `-ldflags="-w"` (strip DWARF debug info) | ~5-10% | Yes |
| `CGO_ENABLED=0` (pure Go, no libc dependency) | Varies | Yes (required for cross-compilation) |
| `-trimpath` (remove build path metadata) | ~1% | Yes |
| Replace heavy dependencies with stdlib | See ADR-001 | Yes (gRPC removed) |
| UPX executable compression | **60-70%** | **This ADR** |

## What is UPX?

[UPX (Ultimate Packer for eXecutables)](https://upx.github.io/) is a free, open-source executable packer that has been in active development since 1996. It compresses executables using the NRV (Not Really Vanished) and LZMA compression algorithms, producing a smaller executable that decompresses itself in memory at load time.

### How UPX works

```
                   Build Time                          Runtime
                   ----------                          -------

 ┌──────────────┐    go build     ┌──────────────┐
 │  Go Source   │  ──────────►    │  ELF Binary   │
 │  (.go files) │   -ldflags      │  7.2 MB       │
 └──────────────┘   "-s -w"       └──────┬───────┘
                                         │
                                    upx --best
                                         │
                                         ▼
                                  ┌──────────────┐
                                  │  UPX Packed   │     execve()      ┌──────────────┐
                                  │  2.6 MB       │  ──────────────►  │  In-Memory    │
                                  │               │   1. Decompress   │  7.2 MB       │
                                  │  ┌──────────┐ │   2. mmap()       │  (original)   │
                                  │  │ UPX Stub │ │   3. Jump to      │               │
                                  │  │ (~10 KB) │ │      entry point  │  Running      │
                                  │  ├──────────┤ │                   │  Agent        │
                                  │  │Compressed│ │                   └──────────────┘
                                  │  │  Payload │ │
                                  │  │ (LZMA)   │ │
                                  │  └──────────┘ │
                                  └──────────────┘
```

1. **At build time**, UPX replaces the original binary with a compressed version containing a small decompressor stub (~10 KB) followed by the LZMA-compressed original binary.
2. **At runtime**, when the OS executes the packed binary, the UPX stub runs first: it decompresses the original binary into memory, then transfers control to the original entry point.
3. The decompression is **transparent** — the running program behaves identically to the uncompressed version. System calls, signal handling, `/proc/self/exe`, and all other OS interfaces work normally.

### UPX is widely used in embedded/IoT

UPX is a standard tool in the embedded Linux ecosystem. Notable users include:
- **BusyBox** distributions for embedded systems
- **Alpine Linux** Docker images (minimizing container layer size)
- **IoT firmware** builders (ESP32, OpenWrt, Buildroot)
- **Kubernetes node agents** (Datadog Agent, Telegraf in constrained environments)

## Decision

Apply UPX compression (`upx --best`) to the agent and watchdog binaries as the final step of the build pipeline.

## Measured Results

All measurements on **linux/arm64** (Raspberry Pi target), `CGO_ENABLED=0`:

| Build Stage | Agent Size | Watchdog Size |
|---|---|---|
| Default (no flags) | 10.4 MB | 2.6 MB |
| Stripped (`-ldflags="-s -w"`) | 7.2 MB | 1.8 MB |
| Stripped + UPX (`--best`) | **2.6 MB** | **689 KB** |

**Compression ratio: 35%** (65% reduction). The agent binary is now **48% under** the 5 MB target, leaving headroom for Phase 3-4 features (mTLS, OTA, VPN).

## Trade-off Analysis

### Startup overhead

UPX adds a one-time decompression step when the binary is first executed.

| Metric | Measured Value | Acceptable? |
|---|---|---|
| Decompression time (RPi 4, ARM Cortex-A72) | ~50-100 ms | Yes — the agent is a long-running daemon; startup cost is amortized over days/weeks of uptime |
| Decompression time (RPi 3, ARM Cortex-A53) | ~100-200 ms | Yes — same reasoning |

For comparison, the agent's startup sequence (BoltDB open, controller registration, MQTT connect) takes 1-3 seconds. The UPX overhead is negligible in context.

### Memory overhead

During decompression, both the compressed and decompressed images briefly coexist in memory:

| Phase | RSS (Resident Set Size) |
|---|---|
| Peak during decompression | ~10 MB (compressed + decompressed) |
| Steady-state after startup | ~7-8 MB (same as uncompressed binary) |
| Agent runtime with BoltDB + goroutines | ~10-15 MB |

The Raspberry Pi 4 has 1-8 GB of RAM. The ~3 MB transient overhead during decompression is inconsequential (0.03-0.3% of available RAM).

### Disk I/O

Packed binaries are **faster to load from slow storage** (SD cards, eMMC) because less data is read from disk. The decompression happens in CPU (fast) while disk I/O is the bottleneck on embedded devices:

- **Unpacked**: read 7.2 MB from SD card (~72 ms at 100 MB/s sequential)
- **Packed**: read 2.6 MB from SD card (~26 ms) + decompress in CPU (~80 ms) = ~106 ms total

On the RPi's Class 10 SD card with realistic random-read speeds (~10-20 MB/s), packed binaries can actually start faster because they read 64% less data from the slow storage medium.

### OTA update bandwidth

The agent binary is transferred over the network during OTA updates. Smaller binaries directly reduce:
- **Download time**: 2.6 MB vs 7.2 MB (64% less bandwidth)
- **Cellular data costs**: relevant for 4G/LTE-connected field devices
- **Update window duration**: faster updates mean shorter vulnerability windows

### Security considerations

UPX-packed binaries are sometimes flagged by antivirus software on desktop operating systems because malware has historically used UPX for obfuscation. This is **not a concern** for EdgeGuardian because:
- The agent runs on headless Linux ARM devices with no antivirus
- The binary is deployed via our own OTA pipeline with Ed25519 signature verification (Phase 3)
- UPX packing is applied at build time in CI, not by the end user

### Debuggability

Packed binaries cannot be inspected with `go tool nm`, `objdump`, or `readelf` (the compressed payload is opaque). This is acceptable because:
- **Development builds** skip UPX — developers build with `go build` directly for debugging
- **Production builds** use UPX via the build script (`scripts/build-agent.sh`)
- `upx -d` can decompress a packed binary back to the original for post-mortem analysis
- Runtime debugging (delve, gdb) works normally since the decompressed binary in memory is identical to the original

## Implementation

### Build script integration

`scripts/build-agent.sh` applies UPX automatically when available:

```bash
# Compress with UPX if available
UPX="${UPX:-$(command -v upx 2>/dev/null || true)}"
if [ -n "$UPX" ]; then
  "$UPX" --best --quiet "$OUTPUT_DIR/edgeguardian-agent-${OS}-${ARCH}"
fi

# Enforce 5 MB size gate — build fails if exceeded
AGENT_SIZE=$(stat -c%s "$AGENT_BIN")
MAX_SIZE=$((5 * 1024 * 1024))
if [ "$AGENT_SIZE" -gt "$MAX_SIZE" ]; then
  echo "FAIL: Agent binary exceeds 5MB target"
  exit 1
fi
```

### UPX installation

UPX is available in all major package managers:

```bash
# Debian/Ubuntu (Raspberry Pi OS)
sudo apt install upx-ucl

# macOS
brew install upx

# Windows
scoop install upx
# or: winget install upx

# Alpine (CI/Docker)
apk add upx

# From source
# https://github.com/upx/upx/releases
```

### CI/CD integration

The build script is designed to work both locally and in CI:
- If UPX is installed, compression is applied automatically
- If UPX is not installed, the build proceeds without compression (development convenience)
- The 5 MB size gate **always** runs, regardless of UPX availability — this catches regressions early

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Accept 7.2 MB binary | Violates the <5 MB project specification; larger OTA payloads |
| Rewrite in C/Rust | Would produce a ~500 KB binary but loses Go's concurrency model, ecosystem, and development velocity — unacceptable for a 4-6 month thesis timeline |
| TinyGo compiler | Does not support `net/http`, `encoding/json`, reflection, or goroutines with full semantics — incompatible with our codebase |
| Replace all external deps with stdlib | Possible for MQTT (write minimal client) but `zap` and `bbolt` have no viable stdlib alternatives. Estimated savings: ~1 MB (still over 5 MB) |
| `gccgo` compiler | Produces smaller binaries but has poor cross-compilation support for ARM and lacks recent Go language features |
| Custom linker scripts | Go does not expose linker script control; the `cmd/link` toolchain is not configurable beyond `-ldflags` |
| Ship as gzipped binary, decompress on device | Adds operational complexity (two-step deploy); UPX is strictly superior since it's a single self-extracting file |

## References

- [UPX: the Ultimate Packer for eXecutables](https://upx.github.io/) — official project page
- [UPX GitHub Repository](https://github.com/upx/upx) — source code (GPLv2+)
- [Go Binary Size — Official FAQ](https://go.dev/doc/faq#Why_is_my_trivial_program_such_a_large_binary) — explains why Go binaries are large
- [Shrinking Go Binaries](https://go.dev/blog/rebuild) — official Go blog on binary size reduction
- Filippo Valsorda, "Shrink your Go binaries with this one weird trick" — community analysis of Go binary bloat sources
