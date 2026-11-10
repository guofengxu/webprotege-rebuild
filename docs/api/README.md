# API contract

`openapi.json` is the committed OpenAPI snapshot of wp-app. `OpenApiContractTest` starts the application, fetches `/v3/api-docs`, and fails unless the live document matches this file (after JSON canonicalization). Errors are RFC 9457 `ProblemDetail`.

## Generate TypeScript types

Frontend packages must not hand-write request or response types (ADR-008). Point `openapi-typescript` 7.x at this snapshot:

```bash
npx openapi-typescript docs/api/openapi.json -o schema.d.ts
```

Once `frontend/packages/api-types` exists (P3-27), generate from that package instead:

```bash
pnpm -C frontend --filter @wp/api-types run gen
```

The generator reads `../../../docs/api/openapi.json` relative to `frontend/packages/api-types`. Import `paths` and `components` from the generated module; alias models such as `EntityNode`, `ProblemDetail`, and the `Update*FrameRequest` bodies from `components["schemas"]`.

## Refresh the snapshot

After changing a controller or a public response type:

```bash
./mvnw -pl :wp-app -Dwp.openapi.update=true test -Dtest=OpenApiContractTest
```

Commit the updated `openapi.json` with the code change.
