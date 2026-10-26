# ADR-0010 — AI agent framework and data-access model

- **Status:** Proposed
- **Date:** 2026-10-26

## Context

Users must analyse devices and measurements with an AI agent hosted on Azure Container Apps that consumes a model through Microsoft Foundry. The agent must respect user permissions and never modify data.

## Decision

1. **The agent is built with Microsoft Agent Framework in Python** and hosted on Azure Container Apps (not as a Foundry-hosted agent).
2. **Models are consumed through a Microsoft Foundry project** with deployments in the **EU data zone**.
3. **The agent accesses platform data only through the platform API**, with the **calling user's token** ([ADR-0007](ADR-0007-user-authn-authz-and-api-exposure.md)) and **read-only tools**; it has no direct database access.

## Why hard to reverse

The agent framework shapes all agent code (tools, threads, streaming, instrumentation, evaluation) — changing it is a rewrite of the agent. The data-access model is a security property (the agent can never see more than the user nor write) on which the API surface, tools design and auditing depend.

## Consequences

- Best-in-class Foundry, tracing and evaluation integration; a second runtime (Python) in the repository ([ADR-0001](ADR-0001-runtime-and-languages.md)).
- Every tool call goes through the gateway and API — consistent authorization and telemetry, at the cost of extra latency; the API must offer aggregate endpoints so the agent does not need one call per device.
- Self-hosting on ACA means the platform owns scaling, conversation state and streaming.

## Alternatives considered

| Option | Why not |
|---|---|
| Foundry Agent Service hosted agent | Requirement: agent hosted on ACA. |
| Java (Spring AI / LangChain4j) | Agent Framework's Python SDK has the most complete Foundry and tracing integration. |
| Text-to-SQL / direct database tool | Bypasses API authorization; risk of expensive or unsafe queries. |
| MCP server in front of the API | Can be added later without changing this decision. |
