# ADR-001: HTTP/JSON Instead of gRPC

## Context

The EdgeGuardian Go agent runs on resource-constrained edge devices (Raspberry Pi, similar ARM SBCs). A hard requirement from the project specification is:

> Agent binary size (linux/arm64, stripped) < 5 MB

All communication between the agent and the Spring Boot controller involves simple request-response patterns:
- Device registration
- Periodic heartbeats
- Desired-state manifest retrieval
- Observed-state reporting

The original Phase 1 prototype used gRPC with protobuf-generated stubs. After measuring the binary size impact, gRPC was removed entirely from the project.

## Decision

Use **HTTP/JSON** for all agent-controller communication. gRPC and protobuf have been fully removed from both the agent and the controller. The data contract is defined by Go structs (`agent/internal/model/`) and Java DTOs (`controller/.../dto/`) with matching JSON field names.

## Measured Evidence

Stripped binaries (`-ldflags="-s -w"`, `CGO_ENABLED=0`, linux/arm64):

| Approach | Binary Size |
|---|---|
| HTTP/JSON (net/http stdlib) | **7.2 MB** (2.6 MB with UPX) |
| gRPC + protobuf | **11 MB** |

gRPC adds **6.7 MB** — the `google.golang.org/grpc` module pulls in HTTP/2, protobuf reflection, service config parsing, the xDS resolver, and other machinery that cannot be tree-shaken by the Go compiler.

## Rationale

### Why not gRPC

- **Binary size is a hard constraint.** 11 MB exceeds the 5 MB target by 120%. The cost is structural: gRPC in Go requires HTTP/2 framing, protobuf runtime, and generated stub code.
- **No feature we need is exclusive to gRPC.** The communication pattern is simple request-response. There are no bidirectional streaming, load-balancing, or service-mesh requirements.
- **Unnecessary complexity.** Maintaining `.proto` files, code generation pipelines (`protoc`), and generated stubs adds build complexity without proportional benefit for this project's scope.

### Why HTTP/JSON is sufficient

- **`net/http` is already in the binary.** Go's stdlib HTTP client is included at zero marginal cost since MQTT and other dependencies already pull it in.
- **JSON encoding is stdlib.** `encoding/json` adds negligible size.
- **Retries with exponential backoff** are implemented in ~50 lines of Go with full control over backoff strategy.
- **Debuggability.** HTTP/JSON traffic is human-readable with `curl`, browser dev tools, or any HTTP proxy — useful for thesis demos and development.

### Why we removed gRPC from the controller too

Initially, the plan was to keep gRPC on the controller for other clients (dashboard, CLI). In practice:
- The Next.js dashboard communicates via REST (standard for web apps)
- No CLI tool has been built or planned that requires gRPC
- Keeping gRPC adds build complexity (protobuf plugin, proto files, generated code) for zero current consumers
- If gRPC is needed in the future, it can be re-added to the controller without affecting the agent

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| gRPC with protobuf | 11 MB binary, exceeds 5 MB target; removed entirely |
| protobuf-over-HTTP (no gRPC runtime) | Still pulls protobuf runtime (~7 MB). Not worth the complexity |
| tinygo compiler | Does not support `net/http`, `encoding/json`, or reflection |
| Cap'n Proto / FlatBuffers | Adds multi-MB overhead and a non-standard dependency |
| MessagePack | Marginal size savings over JSON, loses human readability |

## Consequences

### Positive
- Agent binary is 2.6 MB with UPX (well under 5 MB budget)
- No code generation step — build is `go build` and `gradle build`, nothing else
- Easier debugging with human-readable HTTP/JSON traffic
- Simpler project structure — no `proto/` directory, no generation scripts

### Negative
- No compile-time schema enforcement — JSON shape mismatches are caught at runtime
- OTA downloads (Phase 3) will use chunked HTTP transfer instead of gRPC server-streaming
- Slightly higher per-message overhead (JSON text vs protobuf binary) — negligible at one message per 30 seconds

### Mitigations
- Shared model types with explicit JSON tags serve as the contract (`agent/internal/model/`, `controller/.../dto/`)
- Integration tests (Phase 5) validate request/response shapes end-to-end
- OTA chunked download is a well-understood HTTP pattern (Range headers, Content-Length)

## Binary Size with UPX

The stripped binary is 7.2 MB on linux/arm64 due to Go's `crypto/tls` stdlib cost. UPX compression brings it to **2.6 MB**. See [ADR-002: UPX Binary Compression](ADR-002-upx-binary-compression.md) for details.