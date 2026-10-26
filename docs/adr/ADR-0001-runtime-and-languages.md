# ADR-0001 — Programming languages and hosting platform

- **Status:** Proposed
- **Date:** 2026-10-26

## Context

The platform has several backend workloads (decoder, APIs, alert service, simulator, migrator), an AI agent and a web UI. Requirements: Java (latest) or another high-performing language, all services on Azure Container Apps (ACA), passwordless access to Azure data services, OpenTelemetry.

## Decision

1. **Platform services are written in Java 25 (LTS) with Spring Boot 4.**
2. **The AI agent is written in Python** (framework choice: [ADR-0010](ADR-0010-ai-agent.md)).
3. **The web UI is a TypeScript single-page application** hosted on Azure Static Web Apps.
4. **All backend workloads are containers hosted on Azure Container Apps**, in one ACA environment per platform environment.

## Why hard to reverse

Language and framework determine every line of service code, the shared libraries (payload codecs shared by decoder and simulator), the build and the team's skills; changing them means rewriting the services. The hosting platform shapes scaling, identity, networking and deployment for all workloads.

## Consequences

- One language for all platform services; codecs and security/observability configuration are shared as internal libraries.
- Spring Cloud Azure passwordless integrations (PostgreSQL, Redis, Event Hubs, Service Bus) reduce custom authentication code.
- JVM start-up/memory is higher than native binaries; acceptable for a demonstrator.
- Two server runtimes (JVM, Python) to build, secure and instrument.

## Alternatives considered

| Option | Why not |
|---|---|
| Java 25 + Quarkus | Better start-up/memory, but fewer out-of-the-box passwordless Azure integrations. |
| Go / .NET 10 | Valid high-performance options, but Java is the stated preference. |
| Java for the agent | See ADR-0010. |
| Azure Functions / AKS for some workloads | Requirement is ACA for all services; one hosting model simplifies identity and operations. |
