---
title: Credential Vault and Credential Proxy
authors:
  - "@jwx0925"
creation-date: 2026-05-28
last-updated: 2026-05-28
status: provisional
---

# OSEP-0012: Credential Vault and Credential Proxy

<!-- toc -->
- [Summary](#summary)
- [Motivation](#motivation)
  - [Goals](#goals)
  - [Non-Goals](#non-goals)
- [Requirements](#requirements)
- [Proposal](#proposal)
  - [Notes/Constraints/Caveats](#notesconstraintscaveats)
  - [Risks and Mitigations](#risks-and-mitigations)
- [Design Details](#design-details)
  - [Terminology](#terminology)
  - [Architecture Overview](#architecture-overview)
  - [Request Flow](#request-flow)
  - [API Schema](#api-schema)
  - [Credential Sources](#credential-sources)
  - [Credential Injection](#credential-injection)
  - [Runtime Modes](#runtime-modes)
  - [Policy and Egress Integration](#policy-and-egress-integration)
  - [Observability](#observability)
  - [Component Changes](#component-changes)
- [Test Plan](#test-plan)
- [Drawbacks](#drawbacks)
- [Alternatives](#alternatives)
- [Infrastructure Needed](#infrastructure-needed)
- [Upgrade & Migration Strategy](#upgrade--migration-strategy)
<!-- /toc -->

## Summary

This proposal introduces **Credential Vault**, a brokered credential layer for OpenSandbox, and **Credential Proxy**, the runtime component that injects scoped credentials into approved outbound requests without exposing plaintext credentials inside the sandbox.

Instead of passing secrets through environment variables, files, or command arguments, users attach credential bindings to a sandbox. Credential Proxy evaluates the sandbox identity, destination, method, path, and injection policy before adding credentials to outbound HTTP requests.

## Motivation

AI agents frequently need credentials to call external systems such as GitHub, model APIs, cloud storage, package registries, databases, and internal services. Today the common approach is to place secrets inside the sandbox as environment variables or files. That makes the secret available to every process in the sandbox, and an untrusted or compromised agent can print, persist, exfiltrate, or transform the secret.

OpenSandbox already provides isolated execution and per-sandbox egress control. The next security requirement is brokered credential use: a sandboxed agent should be able to use an approved credential for an approved destination, but it should not be able to read the underlying plaintext credential.

Credential Vault extends OpenSandbox's sandbox security model from:

- where code can run,
- what network destinations it can reach,

to:

- what credentials it can use for those destinations.

### Goals

1. **Brokered credentials**: Let sandboxed workloads use credentials without receiving plaintext secret values in the sandbox environment, filesystem, command line, or user-visible process output.
2. **Declarative binding**: Add a sandbox creation-time credential binding model that describes source, scope, and injection behavior.
3. **Policy-aware runtime injection**: Inject credentials only when sandbox identity, destination FQDN, HTTP method, and path all match the binding.
4. **Egress alignment**: Integrate with `networkPolicy.egress` so credential scope and network reachability are consistent.
5. **Runtime agnostic**: Support both Docker and Kubernetes through the existing egress sidecar pattern that shares the sandbox network namespace.
6. **Transparent by default**: Use the existing egress transparent mitmproxy path as Credential Proxy so applications do not need proxy or base URL changes.
7. **Auditable and redacted**: Emit useful audit events and metrics while redacting credential material from logs, diagnostics, and responses.
8. **Backward compatible**: Keep existing sandbox creation and egress behavior unchanged unless credential bindings are explicitly requested.

### Non-Goals

1. **General-purpose secret manager**: Credential Vault is not intended to replace HashiCorp Vault, Infisical, cloud secret managers, or Kubernetes Secret. It brokers credentials from configured sources into sandbox traffic.
2. **Secret lifecycle management**: Rotation, versioning, approval workflows, and cross-environment secret synchronization are out of scope for the initial design.
3. **Plaintext exposure inside sandbox**: This proposal does not add an API for sandbox processes to retrieve raw credential values.
4. **Generic body rewriting as MVP**: Request/response body mutation is out of scope for the MVP; header injection is sufficient for the first set of credential use cases.
5. **Per-process policy**: Credential policies apply to a sandbox, not to individual processes inside that sandbox.
6. **Non-HTTP protocols as MVP**: SSH, database wire protocols, Git smart protocol credential helpers, and arbitrary TCP credential injection are future work.
7. **Replacing egress policy**: Credential Vault complements egress control but does not replace network allow/deny enforcement.

## Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| R1 | Users can attach credential bindings to a sandbox at creation time | Must Have |
| R2 | Plaintext credentials are not exposed through sandbox env vars, files, lifecycle API responses, command output, or diagnostic APIs | Must Have |
| R3 | Credential Proxy injects credentials only for matching FQDN, HTTP method, and path scope | Must Have |
| R4 | Initial injection supports HTTP request headers | Must Have |
| R5 | Kubernetes Secret and server-local configuration can be used as credential sources | Must Have |
| R6 | Credential bindings can be validated against `networkPolicy.egress` when both are present | Should Have |
| R7 | Audit logs and metrics identify binding usage without logging credential values | Must Have |
| R8 | Docker and Kubernetes runtimes use the same user-facing API semantics | Must Have |
| R9 | Credential Proxy is default-deny for missing, invalid, or non-matching bindings | Must Have |
| R10 | The runtime uses egress transparent mitmproxy as the Credential Proxy implementation | Must Have |
| R11 | Future secret managers can be added through a provider interface | Should Have |

## Proposal

Add Credential Vault as a lifecycle API and server-side control-plane capability. Add Credential Proxy as the credential-aware runtime behavior of the existing egress transparent mitmproxy path.

The first implementation supports **transparent proxy mode**:

1. The user creates a sandbox with `credentialVault.bindings`.
2. OpenSandbox server validates bindings, resolves source references, and enables egress transparent mitmproxy for the sandbox.
3. The sandbox application container sends normal outbound HTTP/HTTPS traffic, for example to `https://api.github.com/repos/alibaba/OpenSandbox`.
4. Egress transparent mitmproxy intercepts outbound `TCP 80/443` traffic in the sandbox network namespace.
5. Credential Proxy evaluates the intercepted request against the sandbox credential bindings.
6. If exactly one binding matches and policy allows the request, Credential Proxy fetches or receives the credential material from a trusted source path and injects it into the request.
7. The external service receives the credential-bearing request; the sandbox process only sees the service response.

At a high level:

```
┌───────────────────────────────────────────────────────────────────────┐
│                         OpenSandbox Server                            │
│                                                                       │
│  ┌──────────────────────┐       ┌──────────────────────────────────┐  │
│  │ Lifecycle API         │       │ Credential Vault Control Plane    │  │
│  │ - create sandbox      │──────▶│ - validate binding                │  │
│  │ - store metadata      │       │ - resolve source reference        │  │
│  │ - start runtime       │       │ - provide credential bootstrap    │  │
│  └──────────────────────┘       └──────────────────────────────────┘  │
└──────────────────────────────────────────┬────────────────────────────┘
                                           │ binding config
                                           ▼
┌───────────────────────────────────────────────────────────────────────┐
│                    Sandbox Pod / Network Namespace                    │
│                                                                       │
│  ┌──────────────────────┐          ┌───────────────────────────────┐  │
│  │ Application Container │HTTP(S)│ Egress Sidecar / Credential     │  │
│  │ - no plaintext secret │────────▶│ Proxy (transparent mitmproxy)  │  │
│  │ - no proxy config     │         │ - policy match                 │  │
│  └──────────────────────┘         │ - injection, redaction, audit  │  │
│                                    └───────────────┬───────────────┘  │
└────────────────────────────────────────────────────┼──────────────────┘
                                                     │ authenticated request
                                                     ▼
                                             External Service
```

### Notes/Constraints/Caveats

1. **Credential Proxy is the egress transparent mitmproxy path**: This proposal does not introduce an explicit proxy or local gateway mode. Applications keep using their normal target URLs.
2. **HTTPS interception requires trusted CA setup**: Transparent HTTPS injection depends on the sandbox trusting the mitmproxy root CA. Images or runtime startup must install the OpenSandbox MITM CA, otherwise HTTPS handshakes fail.
3. **Credential source access is control-plane trusted**: Runtime sidecars should not be granted broad cluster secret access. The server should resolve or mint scoped runtime material for only the requested sandbox bindings.
4. **Credential material must be short-lived in memory**: Credential Proxy should not persist plaintext credentials on disk. If temporary files are unavoidable for runtime bootstrap, they must be mounted read-only, scoped to one sandbox, and cleaned up with sandbox deletion.
5. **Binding scope must be narrower than or equal to egress scope**: A binding that injects a credential for a destination not allowed by egress policy should fail validation or produce a warning depending on compatibility mode.
6. **Multiple matching bindings are ambiguous**: If more than one binding matches a request and no deterministic precedence is declared, Credential Proxy must fail closed.

### Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Sandbox bypasses Credential Proxy | Credential not injected, or traffic reaches destination without policy mediation | Use egress transparent redirect for TCP 80/443 and recommend `networkPolicy.defaultAction=deny` with `dns+nft` |
| Credential leakage through logs | Secret exposure | Central redaction helpers; never log injected headers or rendered values; regression tests for logs |
| Credential source over-permissioned to sidecars | Cluster-wide secret access risk | Server resolves sources and passes only sandbox-scoped material; sidecar has no Kubernetes API permission by default |
| Binding and egress policy drift | Credential may be configured for unreachable or unintended destinations | Validate binding targets against `networkPolicy.egress`; expose diagnostics for mismatches |
| Header injection into wrong host due to redirects | Credential sent to unintended destination | Re-evaluate policy after each redirected request; strip injected credentials on cross-host redirect unless target scope matches |
| HTTPS CA not trusted by sandbox image | Authenticated HTTPS requests fail | Install/export the OpenSandbox mitmproxy CA during sandbox startup or document image requirements |
| Multiple bindings match one request | Wrong credential injection | Fail closed unless a single highest-priority binding is configured |
| Long-lived credentials remain in proxy memory | Expanded exposure window | Cache with TTL, zero buffers where practical, prefer short-lived tokens from providers |
| Users expect full secret management | Product confusion | Document Credential Vault as a broker layer, not a standalone secret manager |

## Design Details

### Terminology

- **Credential Vault**: OpenSandbox control-plane capability for declaring, validating, and managing credential bindings on sandboxes.
- **Credential Proxy**: Credential-aware runtime behavior in the egress sidecar's transparent mitmproxy path. It evaluates outbound HTTP/HTTPS requests and injects credentials when policy matches.
- **Credential Binding**: A per-sandbox declaration that connects a credential source to an allowed destination and injection rule.
- **Credential Source**: A trusted source of credential material, such as Kubernetes Secret or server-local configuration.
- **Credential Injection**: The act of adding credential material to an outbound request, for example as an `Authorization` header.

### Architecture Overview

Credential Vault should be modeled as a control-plane extension of sandbox lifecycle. Credential Proxy should be modeled as a credential-aware extension of the existing egress sidecar transparent mitmproxy path:

- Egress sidecar controls which network destinations are reachable.
- Credential Proxy controls which credentials are attached to allowed outbound HTTP/HTTPS requests.

For Kubernetes, this means the existing egress sidecar in the sandbox Pod starts mitmproxy transparent mode and loads OpenSandbox's credential addon. For Docker, this means the existing egress sidecar shares the sandbox network namespace, redirects outbound `80/443` traffic to mitmproxy, and runs the same credential addon.

The egress sidecar already has the transparent MITM primitives required for Credential Proxy:

- starts `mitmdump --mode transparent`,
- redirects outbound `TCP 80/443` traffic to the mitmproxy listener using `iptables`,
- loads system and user mitm addons,
- exports the mitmproxy root CA,
- exposes health readiness so sandboxes do not start before interception is ready.

Credential Vault adds a first-party credential addon and binding bootstrap config to that path.

### Request Flow

For a GitHub read-only token binding:

1. Sandbox process calls `https://api.github.com/repos/alibaba/OpenSandbox` normally.
2. Egress transparent mitmproxy intercepts the request and exposes host `api.github.com`, method `GET`, and path `/repos/alibaba/OpenSandbox` to the Credential Proxy addon.
3. Credential Proxy loads matching bindings for the sandbox.
4. It finds `github-readonly` where:
   - `targets` contains `api.github.com`,
   - `methods` contains `GET`,
   - `paths` contains `/repos/*`,
   - injection type is `header`.
5. It retrieves credential material from the scoped source path.
6. It sends the upstream request with:

```http
Authorization: Bearer <redacted>
```

7. It records an audit event with sandbox ID, binding name, target, method, path pattern, decision, and response status. The credential value is not logged.

### API Schema

Extension to `specs/sandbox-lifecycle.yml`:

```yaml
components:
  schemas:
    CreateSandboxRequest:
      properties:
        credentialVault:
          $ref: '#/components/schemas/CredentialVaultSpec'

    CredentialVaultSpec:
      type: object
      properties:
        mode:
          type: string
          enum: [transparentProxy]
          default: transparentProxy
        bindings:
          type: array
          items:
            $ref: '#/components/schemas/CredentialBinding'
      additionalProperties: false

    CredentialBinding:
      type: object
      required: [name, sourceRef, scope, injection]
      properties:
        name:
          type: string
          description: Sandbox-local credential binding name.
        sourceRef:
          $ref: '#/components/schemas/CredentialSourceRef'
        scope:
          $ref: '#/components/schemas/CredentialScope'
        injection:
          $ref: '#/components/schemas/CredentialInjection'
      additionalProperties: false

    CredentialSourceRef:
      type: object
      required: [type, name]
      properties:
        type:
          type: string
          enum: [kubernetesSecret, serverLocal]
        name:
          type: string
        key:
          type: string
      additionalProperties: false

    CredentialScope:
      type: object
      required: [targets]
      properties:
        targets:
          type: array
          items:
            type: string
          description: FQDN or wildcard domain targets, for example api.github.com or *.example.com.
        methods:
          type: array
          items:
            type: string
          default: [GET, POST, PUT, PATCH, DELETE]
        paths:
          type: array
          items:
            type: string
          default: ["/*"]
      additionalProperties: false

    CredentialInjection:
      type: object
      required: [type, name, value]
      properties:
        type:
          type: string
          enum: [header]
        name:
          type: string
          example: Authorization
        value:
          type: string
          example: Bearer {{ credential }}
      additionalProperties: false
```

Example request:

```json
{
  "image": "python:3.12",
  "networkPolicy": {
    "defaultAction": "deny",
    "egress": [
      { "action": "allow", "target": "api.github.com" }
    ]
  },
  "credentialVault": {
    "mode": "transparentProxy",
    "bindings": [
      {
        "name": "github-readonly",
        "sourceRef": {
          "type": "kubernetesSecret",
          "name": "github-readonly-token",
          "key": "token"
        },
        "scope": {
          "targets": ["api.github.com"],
          "methods": ["GET"],
          "paths": ["/repos/*", "/search/*"]
        },
        "injection": {
          "type": "header",
          "name": "Authorization",
          "value": "Bearer {{ credential }}"
        }
      }
    ]
  }
}
```

### Credential Sources

The MVP supports two source types.

1. **Kubernetes Secret**
   - Available only for Kubernetes runtime.
   - The OpenSandbox server reads the referenced secret through its existing service account permissions.
   - Credential Proxy does not receive Kubernetes API permissions by default.
   - The resolved value is passed to the proxy through a sandbox-scoped secret volume or bootstrap channel.

2. **Server-local source**
   - Available for Docker and local development.
   - Configured in server TOML, for example:

```toml
[credential_vault]
enabled = true

[[credential_vault.sources]]
type = "server_local"
name = "github-readonly-token"
value_env = "OPENSANDBOX_GITHUB_READONLY_TOKEN"
```

Future providers may include HashiCorp Vault, Infisical, AWS Secrets Manager, GCP Secret Manager, Azure Key Vault, and internal credential brokers.

### Credential Injection

The MVP supports request header injection only. Credential Proxy injects the header into intercepted HTTP/HTTPS requests after the transparent mitmproxy path has decoded request metadata:

```yaml
injection:
  type: header
  name: Authorization
  value: "Bearer {{ credential }}"
```

Rules:

- `{{ credential }}` is the only supported template variable in the MVP.
- Credential Proxy must reject templates that do not include `{{ credential }}`.
- Credential Proxy must reject attempts to inject hop-by-hop proxy headers unless explicitly allowed by implementation.
- Credential Proxy must remove any existing request header with the same name before injecting a credential, unless a future merge strategy is added.
- On redirect, Credential Proxy must re-evaluate target scope before preserving injected headers.

### Runtime Modes

The initial supported runtime mode is **transparent proxy**.

#### Transparent Proxy Mode

The runtime enables egress transparent mitmproxy when `credentialVault.bindings` is present. The application container keeps using normal outbound URLs. Credential Proxy runs as an OpenSandbox-managed mitm addon loaded by the egress sidecar.

Advantages:

- No application proxy or base URL changes.
- Reuses existing egress sidecar network namespace, `iptables` redirect, health gate, and mitmproxy integration.
- Works with existing HTTP clients, SDKs, CLIs, and agent-generated code as long as they use TCP `80/443` and trust the sandbox CA.
- Keeps credential policy enforcement at the egress boundary, where network policy is already enforced.

Limitations:

- Requires Linux network namespace support and `CAP_NET_ADMIN` for the egress sidecar.
- Requires the sandbox to trust the mitmproxy CA for HTTPS interception.
- Applies to HTTP/HTTPS traffic on `80/443`; non-HTTP protocols need future designs.
- In `ignore_hosts` pass-through mode, Credential Proxy cannot inspect or inject credentials for those hosts.

### Policy and Egress Integration

When both `credentialVault` and `networkPolicy` are present, the server should validate destination consistency.

Recommended validation:

- Every credential binding target must be covered by an allow rule in `networkPolicy.egress`.
- If `networkPolicy.defaultAction` is `allow`, the server should warn that credential-bearing requests may coexist with broad outbound access.
- If a binding target is not reachable under egress policy, sandbox creation should fail with HTTP 400 in strict mode.

Suggested configuration:

```toml
[credential_vault]
enabled = true
egress_validation = "strict" # strict | warn | disabled
```

Credential Proxy remains fail-closed even if egress validation is disabled.

### Observability

Credential Proxy should emit structured logs and metrics without credential values.

Suggested audit log fields:

- `sandbox_id`
- `credential_binding`
- `source_type`
- `target_host`
- `method`
- `path_pattern`
- `decision` (`injected`, `denied`, `no_match`, `ambiguous_match`, `source_error`)
- `status_code`
- `duration_ms`
- `request_id`

Suggested metrics:

- `opensandbox_credential_proxy_requests_total`
- `opensandbox_credential_proxy_injections_total`
- `opensandbox_credential_proxy_denials_total`
- `opensandbox_credential_proxy_source_errors_total`
- `opensandbox_credential_proxy_request_duration_seconds`

All diagnostics APIs that surface runtime logs must preserve redaction behavior.

### Component Changes

#### Specs

- Add `credentialVault` schemas to `specs/sandbox-lifecycle.yml`.
- Add examples for sandbox creation with credential bindings.
- Consider a future `credential-proxy-api.yaml` only if runtime policy inspection/mutation is exposed separately from the egress API.

#### Server

- Add config model for `[credential_vault]`.
- Add source provider interface.
- Validate `CreateSandboxRequest.credentialVault`.
- Persist credential binding metadata without plaintext credential values.
- Resolve or prepare sandbox-scoped credential material during sandbox creation.
- Enable egress transparent mitmproxy and credential addon bootstrap for Docker and Kubernetes runtimes when bindings are present.

#### Components / Egress

- Extend `components/egress` transparent mitmproxy support with a first-party credential addon.
- Load credential binding bootstrap config into the egress sidecar.
- Implement binding evaluation, header injection, redaction, and audit events in the mitm addon path.
- Keep the existing system addon behavior for streaming and user addon loading.

#### Kubernetes

- Enable the egress sidecar with transparent mitmproxy when credential bindings are present.
- Add secret projection or bootstrap delivery for sandbox-scoped credential material.
- Ensure Credential Proxy has no broad Kubernetes API permissions by default.
- Ensure the mitmproxy CA is trusted by the sandbox application container when HTTPS interception is enabled.

#### Docker

- Enable the egress sidecar with transparent mitmproxy sharing the sandbox network namespace.
- Ensure the mitmproxy CA is trusted by the sandbox application container when HTTPS interception is enabled.
- Clean up sidecar and sandbox-scoped credential material when the sandbox is deleted.

#### SDKs and CLI

- Add typed request models for credential bindings.
- Add examples for common providers such as GitHub and model APIs.
- CLI may include validation helpers, but it should not print credential values.

## Test Plan

### Unit Tests

- Schema validation accepts valid bindings and rejects missing `name`, `sourceRef`, `scope`, or `injection`.
- FQDN, wildcard, method, and path matching work as expected.
- Multiple matching bindings fail closed.
- Existing headers with the injection name are replaced or rejected according to the selected implementation rule.
- Redaction removes credential values from logs and errors.
- Egress validation catches binding targets not allowed by `networkPolicy.egress`.

### Integration Tests

- Docker sandbox with server-local source can call a mock HTTP/HTTPS server that requires an injected header without setting proxy or base URL configuration.
- Docker sandbox cannot read credential value from environment variables, mounted files, lifecycle API response, command output, or diagnostics.
- Kubernetes sandbox with Kubernetes Secret source can call a mock HTTP/HTTPS server that requires an injected header without setting proxy or base URL configuration.
- Credential Proxy denies non-matching hosts, paths, and methods.
- Cross-host redirect strips or re-evaluates injected credentials.
- Sandbox deletion cleans up Credential Proxy and any sandbox-scoped credential material.

### E2E Tests

- Create a sandbox with `networkPolicy.defaultAction=deny`, allow `api.github.com`, bind a read-only GitHub credential, and verify a normal `https://api.github.com/...` call succeeds through Credential Proxy.
- Verify direct access to a non-allowed domain fails under egress policy.
- Verify logs and diagnostic APIs never contain the credential string.

## Drawbacks

- Requires enabling transparent MITM for credential-bearing HTTP/HTTPS traffic.
- Adds a new control-plane surface and a credential-aware path inside egress.
- Users may confuse Credential Vault with a full secret management system.
- Debugging outbound requests becomes more complex because credentials are injected outside the application process.
- Header injection covers common API use cases but not all credential workflows, such as SSH private keys or database passwords.

## Alternatives

### Inject Secrets as Environment Variables

This is simple and already common, but it exposes plaintext credentials to the sandbox process. It does not satisfy the primary security goal.

### Mount Secrets as Files

This avoids environment variable leakage but still exposes plaintext credentials to sandbox processes. Agents can read, print, copy, or upload the files.

### Rely Only on External Secret Managers

External secret managers are still needed as sources, but sandbox workloads would need secret manager credentials to fetch secrets directly. That moves the same exposure problem into a different API.

### SDK-only Credential Clients

SDK mediation can be safer and more structured, but it requires language-specific client changes and does not cover existing CLIs, package managers, curl, git-over-HTTPS, or arbitrary agent-generated code. Credential Proxy works at the runtime egress boundary.

### Explicit Proxy or Local Gateway First

Explicit proxy and local gateway modes avoid transparent network interception, but they require application configuration and do not match the current OpenSandbox egress direction. The existing egress transparent mitmproxy path already provides the correct runtime interception point for Credential Proxy.

## Infrastructure Needed

- No new Credential Proxy component image for the MVP; Credential Proxy is implemented in the existing egress image through transparent mitmproxy and a first-party credential addon.
- Server configuration for credential source providers.
- Kubernetes RBAC for server-side secret reads where Kubernetes Secret sources are enabled.
- CI tests for Docker and Kubernetes runtime paths.
- Documentation and examples for common credential binding patterns.
- Sandbox image or runtime support for trusting the OpenSandbox mitmproxy CA.

No new required external service is introduced by the MVP.

## Upgrade & Migration Strategy

Credential Vault is opt-in. Existing sandboxes, SDK calls, egress policies, and runtime behavior remain unchanged when `credentialVault` is omitted.

Recommended rollout:

1. Add schema and server validation behind `[credential_vault].enabled = false` by default.
2. Extend egress transparent mitmproxy with credential addon support and server-local source for local development.
3. Implement Kubernetes Secret source and egress sidecar credential bootstrap.
4. Add SDK models and CLI examples.
5. Document production guidance: use `networkPolicy.defaultAction=deny`, keep credential targets narrow, avoid broad methods and paths, and monitor audit events.

No migration is required for existing users. Users currently injecting secrets through environment variables can gradually migrate by moving those values into configured credential sources and letting Credential Proxy inject them into normal outbound HTTP/HTTPS calls.
