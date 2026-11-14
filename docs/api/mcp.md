# MCP endpoint

wp-app exposes a read-only [Model Context Protocol](https://modelcontextprotocol.io/) server so AI agents can browse ontologies with the same permissions as the REST API.

## Endpoint and authentication

- URL: `POST` / Streamable HTTP at `/mcp` (same host as the REST API).
- Authentication: request header `X-API-Key` only. Session cookies and bearer tokens are ignored on `/mcp`.
- `Origin`: if the client sends an `Origin` header, it must be listed in `wp.cors.allowed-origins`. Clients that omit `Origin` (typical non-browser MCP clients) are allowed.

Issue an API key in the Web app under **Personal settings** (same keys as `POST /api/me/api-keys`).

### Client configuration example

```json
{
  "mcpServers": {
    "wp-ontology": {
      "url": "https://example.invalid/mcp",
      "headers": {
        "X-API-Key": "YOUR_API_KEY"
      }
    }
  }
}
```

Replace the URL and key with your deployment. Do not commit real hostnames or keys.

## Result envelope

Every successful `tools/call` returns one text content item whose JSON shape is:

```json
{
  "result": {},
  "truncated": false,
  "hint": null
}
```

`result` matches the corresponding REST response body. When the serialized payload exceeds `wp.mcp.max-result-bytes` (default 262144), array or paged list results keep as many complete entries as fit, set `truncated` to `true`, and put a short next-step hint in `hint`. Non-list objects that still exceed the limit return `isError: true` with `code = RESULT_TOO_LARGE`.

Application failures use `isError: true` and a `{ "code", "message" }` body (for example `PERMISSION_DENIED`, `VALIDATION_FAILED`, `NOT_FOUND`).

## Tools

| Tool | Purpose | Parameters | REST | Permission |
|---|---|---|---|---|
| `search_entities` | Search entities by display or local name | `projectId`, `query`, optional `types`, optional `limit` (default 20) | `GET /api/projects/{id}/search` (`searchEntities`) | `VIEW_PROJECT` |
| `list_projects` | List projects the caller can access | _(none)_ | `GET /api/projects` (`listProjects`) | _(caller’s project membership)_ |
| `get_hierarchy_roots` | List hierarchy roots | `projectId`, `kind` | `GET /api/projects/{id}/hierarchies/{kind}/roots` (`getHierarchyRoots`) | `VIEW_PROJECT` |
| `get_hierarchy_children` | List direct children of one node | `projectId`, `kind`, `parent` | `GET /api/projects/{id}/hierarchies/{kind}/children` (`getHierarchyChildren`) | `VIEW_PROJECT` |
| `get_hierarchy_paths` | Paths from roots to one entity | `projectId`, `kind`, `entity` | `GET /api/projects/{id}/hierarchies/{kind}/paths` (`getHierarchyPaths`) | `VIEW_PROJECT` |
| `get_entity_frame` | Read one entity frame | `projectId`, `entityType`, `iri` | `GET /api/projects/{id}/frames/...` (`getClassFrame` and siblings) | `VIEW_PROJECT` |
| `list_individuals` | Page direct individuals of a class | `projectId`, `cls`, optional `page` (default 0), optional `size` (default 50, max 200) | `GET /api/projects/{id}/individuals` (`listIndividualsByType`) | `VIEW_PROJECT` |
| `list_revisions` | Page project revisions (newest first) | `projectId`, optional `page` (default 0), optional `size` (default 20, max 200) | `GET /api/projects/{id}/revisions` (`listRevisions`) | `VIEW_CHANGES` |
| `get_revision_diff` | Axiom changes in one revision | `projectId`, `revision`, optional `limit` (default 200, max 200) | `GET /api/projects/{id}/revisions/{rev}` (`getRevisionDiff`) | `VIEW_CHANGES` |
| `list_entity_revisions` | Revisions that mention one entity | `projectId`, `iri`, optional `page` (default 0), optional `size` (default 50, max 200) | `GET /api/projects/{id}/entities/history` (`listEntityRevisions`) | `VIEW_CHANGES` |

`kind` values: `class`, `object-property`, `data-property`, `annotation-property`.  
`entityType` values: `class`, `object-property`, `data-property`, `annotation-property`, `named-individual`.  
IRIs must be absolute.

## Limits and usage tips

- Tools are **read-only**. There are no MCP tools for create, edit, delete, or revert.
- Cap large results with pagination (`page` / `size`) or a smaller `limit` on search and diffs.
- Expand hierarchies one level at a time with `get_hierarchy_children` instead of requesting deep trees.
- Result size is capped by `wp.mcp.max-result-bytes`. Prefer smaller pages when `truncated` is `true`.

## Security

**An API key carries the full authority of its owning user.** The same key can call every REST endpoint under `/api/**`, including write operations. MCP merely omits write tools; it does not restrict the key.

Recommendations:

- Create a dedicated account for agents.
- Grant that account only `CAN_VIEW` (or the minimum needed) on the projects it should see.
- Revoke leaked keys under **Personal settings**.
- Scoped or read-only API keys are not available yet; that requires a separate change.
