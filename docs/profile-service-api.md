# Profile Service API

`profile-service` owns public member profiles. Authentication and account permissions still belong to `auth-service`.

Base path through the gateway is `/api/profile`.

## Envelope

All JSON responses use the same envelope:

```json
{
  "ok": true,
  "data": {},
  "message": null
}
```

Errors return `ok: false`, `data: null`, and a human-readable `message`.

## Authentication

Use `Authorization: Bearer <token>` for private endpoints.

Public endpoints:

- `GET /api/profile/members`
- `GET /api/profile/members/{profileId}`

Private endpoints:

- `GET /api/profile/me`
- `PUT /api/profile/me`
- `GET /api/profile/admin/members`
- `PUT /api/profile/admin/members/{profileId}`

Admin endpoints require an auth role of `SUPER_ADMIN` or `ADMIN`.

## Profile Object

```json
{
  "id": "profile-12345678",
  "userId": "user-12345678",
  "displayName": "North Star",
  "bio": "Builder and redstone maintainer",
  "avatarUrl": "https://example.com/avatar.png",
  "minecraftId": "NorthStar",
  "minecraftUuid": "uuid-value",
  "skinUrl": "https://minotar.net/skin/uuid-value",
  "memberGroup": "MEMBER",
  "memberStatus": "ACTIVE",
  "visibility": "PUBLIC",
  "joinedAt": 1778597497353,
  "createdAt": 1778597497353,
  "updatedAt": 1778597497353,
  "featured": false,
  "exists": true
}
```

`adminNote`, `createdBy`, and `updatedBy` are internal fields and are not returned by the API.

## GET /health

Returns service health.

```json
{
  "ok": true,
  "data": {
    "service": "beiming-profile-service"
  },
  "message": null
}
```

## GET /api/profile/me

Returns the current user's profile. If no profile exists, the service returns a draft view with `exists: false`.

Requires login.

## PUT /api/profile/me

Creates or updates the current user's public profile fields.

Requires login.

Allowed request fields:

```json
{
  "displayName": "North Star",
  "bio": "Builder and redstone maintainer",
  "avatarUrl": "https://example.com/avatar.png",
  "minecraftId": "NorthStar",
  "minecraftUuid": "uuid-value",
  "visibility": "PUBLIC"
}
```

User updates cannot change `memberGroup`, `memberStatus`, `joinedAt`, `featured`, or `adminNote`.

`minecraftId` is required and unique across profiles, case-insensitive.

## GET /api/profile/members

Returns public active members only.

Query parameters:

- `page`, default `1`
- `pageSize`, default `20`, max `100`
- `q`, optional search text for display name or Minecraft ID

Response data:

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 0
}
```

## GET /api/profile/members/{profileId}

Returns a public active profile.

Private or hidden profiles return `404` to guests. The profile owner and admins can still read them with a valid token.

## GET /api/profile/admin/members

Returns all profiles for admins.

Query parameters match the public list endpoint.

## PUT /api/profile/admin/members/{profileId}

Updates admin-managed profile fields.

Requires admin.

Allowed request fields:

```json
{
  "displayName": "North Star",
  "bio": "Builder and redstone maintainer",
  "avatarUrl": "https://example.com/avatar.png",
  "minecraftId": "NorthStar",
  "minecraftUuid": "uuid-value",
  "memberGroup": "TRAINEE",
  "memberStatus": "INACTIVE",
  "visibility": "MEMBER_ONLY",
  "joinedAt": 1778597497353,
  "featured": true,
  "adminNote": "Internal note"
}
```

Allowed enum values:

- `memberGroup`: `MEMBER`, `TRAINEE`, `ADMIN`
- `memberStatus`: `ACTIVE`, `INACTIVE`, `LEFT`, `HIDDEN`
- `visibility`: `PUBLIC`, `MEMBER_ONLY`, `PRIVATE`
