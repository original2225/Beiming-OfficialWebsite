# Beiming Console

Beiming is a server management console built with a React frontend, Spring Boot microservices, and a Go daemon deployed on managed nodes.

## Architecture

```text
frontend
  React frontend (:5173)
    -> backend/api-gateway (:8787)
      Spring Boot API Gateway
        -> backend/auth-service (:8792)
          User registration, login, sessions
        -> backend/resource-service (:8791)
          Remote node configuration and image search

React resource data plane
  -> backend/node-daemon (:8790 on each node)
    Go single-binary executor
```

The gateway is the stable REST entry point and validates user sessions for control-plane APIs. Auth Service owns login, registration, and user sessions. Resource Service owns remote node configuration. Container, VM, file, metric, and realtime operations connect directly from the browser to the selected node daemon.

## Layout

```text
frontend                    React frontend
backend/api-gateway         Spring Boot API gateway
backend/auth-service        Spring Boot auth microservice
backend/resource-service    Spring Boot resource microservice
backend/node-daemon         Go daemon for managed nodes
prisma                      Database schema and migrations
scripts                     Local development launchers
```

## Development

```bash
npm --prefix frontend install
npm run dev:full
```

Windows one-file launcher:

```powershell
npm run start:beiming
```

Individual services:

```bash
npm run dev          # React frontend
npm run dev:api      # Spring Boot API Gateway
npm run dev:auth     # Spring Boot Auth Service
npm run dev:resource # Spring Boot Resource Service
```

Build checks:

```bash
npm run build
npm run spring:build
cd backend/node-daemon && go test ./...
```

Runtime node configuration is stored in `data/remote-nodes.json`. Users and sessions are stored by Auth Service in PostgreSQL. The `data/` directory is intentionally ignored by Git because it may contain daemon tokens and legacy local account data.
