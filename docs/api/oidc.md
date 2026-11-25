# OpenID Connect login

Optional enterprise login via the OAuth 2.0 authorization-code flow. When enabled, the browser is redirected to the identity provider; after a successful callback the server establishes the **same session** as password login (principal = user id string, CSRF cookie, `SessionRegistry` entry). Logout ends only this application's session (no RP-Initiated Logout).

Gate the feature with `wp.oidc.enabled=true`. Client credentials use the standard Spring Security properties under `spring.security.oauth2.client.*`. Binding and provisioning policy live under `wp.oidc.providers.<registrationId>.*`.

Callback URL for each registration:

```text
{baseUrl}/login/oauth2/code/{registrationId}
```

Behind a reverse proxy, set forwarded headers so `{baseUrl}` is correct (`X-Forwarded-Proto`, `X-Forwarded-Host`, and Spring's forwarded-header support).

## Binding rules

Resolution order (see `ExternalIdentityService`):

1. Existing `(issuer, subject)` row in `user_identities`.
2. If `link-by-verified-email=true` and the ID token has a verified email that matches exactly one active non–system-admin user → bind.
3. If `auto-provision=true` → create a local user from `preferred_username` (no password, empty roles) and bind.

Notes:

- Trust only `iss` and `sub` from the ID token for identity. Enable `link-by-verified-email` only for providers you trust to assert email ownership. Entra ID and similar providers often omit `email_verified`; treat missing verification as false.
- System administrator accounts are **never** auto-linked by email. Bind them by inserting into `user_identities` operationally when needed.
- Auto-provisioned users have no password hash; password login returns `PASSWORD_RESET_REQUIRED`.

## Keycloak example (placeholders)

```yaml
wp:
  oidc:
    enabled: true
    providers:
      keycloak:
        display-name: Company SSO
        link-by-verified-email: false
        auto-provision: false

spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            provider: keycloak
            client-id: webprotege
            client-secret: ${OIDC_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            scope: openid,profile,email
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            client-name: keycloak
        provider:
          keycloak:
            issuer-uri: https://keycloak.example.com/realms/example
```

## Generic provider example (explicit endpoints)

Use this shape when the provider has no usable `issuer-uri` discovery document in your environment:

```yaml
wp:
  oidc:
    enabled: true
    providers:
      corp:
        display-name: Corporate IdP
        link-by-verified-email: true
        auto-provision: false

spring:
  security:
    oauth2:
      client:
        registration:
          corp:
            provider: corp
            client-id: webprotege-web
            client-secret: ${OIDC_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            scope: openid,profile,email
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
        provider:
          corp:
            authorization-uri: https://idp.example.com/oauth2/authorize
            token-uri: https://idp.example.com/oauth2/token
            jwk-set-uri: https://idp.example.com/oauth2/jwks
            user-info-uri: https://idp.example.com/oauth2/userinfo
            user-name-attribute: sub
```

List configured providers (empty when disabled): `GET /api/auth/providers` → `[{ id, displayName, loginUrl }]`, where `loginUrl` is `/oauth2/authorization/{id}`.
