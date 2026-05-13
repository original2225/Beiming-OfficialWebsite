# Beiming Backend

Each service owns a deployable runtime boundary.

- `api-gateway`: public REST entry point for the frontend.
- `auth-service`: user registration, login, session validation, and user listing.
- `profile-service`: public member profiles, Minecraft identity, and member directory views.
- `resource-service`: resource control-plane service for remote node configuration and Docker image search.
- `node-daemon`: Go daemon deployed on managed nodes to execute Docker, file, VM, and terminal operations.
