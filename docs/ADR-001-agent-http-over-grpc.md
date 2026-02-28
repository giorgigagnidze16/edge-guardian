# ADR-001: HTTP/JSON over gRPC for Agent-Controller Communication

## Context

The EdgeGuardian Go agent runs on resource-constrained edge devices (Raspberry Pi, similar ARM SBCs). A hard requirement from the project specification is:

> Agent binary size (linux/arm64, stripped) < 5 MB

The agent needs to communicate with the Spring Boot controller for:
- Device registration
- Periodic heartbeats
- Desired-state manifest retrieval
- Observed-state reporting

The original Phase 1 implementation used gRPC with protobuf-generated stubs, matching the `.proto` contract definitions shared between agent and controller.

## Decision

Replace gRPC with plain **HTTP/JSON using Go's `net/http` stdlib** on the agent side. The controller continues to expose both gRPC and REST endpoints.

## Measured Evidence

Stripped binaries (`-ldflags="-s -w"`, `CGO_ENABLED=0`) with identical non-networking dependencies (MQTT, BoltDB, zap, YAML):

| Approach | Binary Size |
|---|---|
| HTTP/JSON (net/http stdlib) | **4.3 MB** |
| gRPC + protobuf | **11 MB** |

gRPC adds **6.7 MB** — the `google.golang.org/grpc` module pulls in HTTP/2, protobuf reflection, service config parsing, the xDS resolver, and other machinery that cannot be tree-shaken by the Go compiler.

## Rationale

### Why not gRPC

- **Binary size is a hard constraint.** 11 MB exceeds the 5 MB target by 120%. There is no linker flag, build tag, or alternative gRPC library that brings this under budget. The cost is structural: gRPC in Go requires HTTP/2 framing, protobuf runtime, and generated stub code.
- **No feature we need is exclusive to gRPC.** The agent's communication pattern is simple request-response. There are no bidirectional streaming, load-balancing, or service-mesh requirements at the agent level.

### Why HTTP/JSON is sufficient

- **`net/http` is already in the binary.** Go's stdlib HTTP client is included at zero marginal cost since other dependencies (MQTT, etc.) already pull it in.
- **JSON encoding is stdlib.** `encoding/json` adds negligible size.
- **Retries, timeouts, and health checks** are straightforward to implement over HTTP (~100 lines of Go) and give the agent full control over backoff strategy.
- **Debuggability.** HTTP/JSON traffic is human-readable with `curl`, browser dev tools, or any HTTP proxy — useful for a thesis demo and development workflow.

### What we preserve

- **Protobuf `.proto` files remain the canonical contract.** They define the data model and are the source of truth. The Go agent's `internal/model` structs mirror these definitions with JSON tags.
- **The controller still serves gRPC.** Other clients (Next.js dashboard via grpc-web, CLI tools, future third-party integrations) can use the strongly-typed gRPC interface.
- **Migration path.** If a future version of the agent targets larger devices where 11 MB is acceptable, switching back to gRPC requires only replacing `http_client.go` with the generated stubs — no architectural changes.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| gRPC with protobuf | 11 MB binary, exceeds 5 MB target |
| protobuf-over-HTTP (no gRPC runtime) | Saves ~3 MB vs full gRPC, but still pulls protobuf runtime (~7 MB total). Not worth the complexity of manual framing. |
| tinygo compiler | Does not support `net/http`, `encoding/json`, or reflection — incompatible with our dependencies |
| Cap'n Proto / FlatBuffers | Smaller than protobuf but still adds multi-MB overhead and introduces a non-standard dependency |
| MessagePack | Marginal size savings over JSON, loses human readability, not worth the trade-off |

## Consequences

### Positive
- Agent binary is 4.3 MB (within the 5 MB budget with headroom for Phase 3-4 features)
- Simpler build — no `protoc` code generation needed for the agent
- Easier debugging during development and thesis demonstrations

### Negative
- No compile-time schema enforcement between agent and controller — JSON shape mismatches are caught at runtime
- Streaming OTA downloads (Phase 3) will use chunked HTTP transfer instead of gRPC server-streaming — requires manual implementation of resume logic
- Slightly higher per-message overhead (JSON text vs protobuf binary) — negligible for the telemetry volumes in this system (one message per 30 seconds)

### Mitigations
- Shared `internal/model` types with explicit JSON tags serve as the agent-side contract
- Integration tests (Phase 5) validate request/response shapes between agent and controller
- OTA chunked download is a well-understood HTTP pattern (Range headers, Content-Length) with ample reference implementations

## Binary Size with UPX

The stripped HTTP/JSON binary is 7.2 MB on linux/arm64 — still over the 5 MB target due to Go's `crypto/tls` and `net/http` stdlib cost. UPX executable compression brings it to **2.6 MB**. See [ADR-002: UPX Binary Compression](ADR-002-upx-binary-compression.md) for the full analysis, trade-offs, and implementation details.
