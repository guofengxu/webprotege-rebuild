# Ontology projection tables (SQL access)

Derived, rebuildable tables for BI tools and AI agents (ADR-013). They mirror a subset of the in-memory ontology and are **not** the source of truth — the revision log is.

## Enable

Set `wp.projection.enabled=true` (default `false`). When enabled, the outbox consumer keeps tables up to date. On first run after enabling, every project that already has revisions gets a full rebuild, at most one project per outbox batch (P6-12).

## Consistency

- Projection is **eventually consistent**. Trust `projection.project_state.rev_no` against the project's revision head.
- Tables are **read-only** for external clients. The application writes under a per-project advisory lock; revision numbers only move forward.
- A full rebuild may replace every row for a project at any time. Do not rely on physical row order, and do not hold long-running transactions against these tables.

## Schema (`V5__projection_schema.sql`)

Schema name: `projection`.

### `project_state`

| Column | Type | Meaning |
|---|---|---|
| `project_id` | `uuid` PK | Project id |
| `rev_no` | `bigint` | Last applied revision number |
| `rebuilt_at` | `timestamptz` | Last full rebuild time |
| `updated_at` | `timestamptz` | Last write (full or incremental) |

### `entities`

| Column | Type | Meaning |
|---|---|---|
| `project_id` | `uuid` | Project id |
| `iri` | `text` | Entity IRI |
| `entity_type` | `text` | One of `CLASS`, `OBJECT_PROPERTY`, `DATA_PROPERTY`, `ANNOTATION_PROPERTY`, `NAMED_INDIVIDUAL`, `DATATYPE` |
| `deprecated` | `boolean` | Whether the entity is deprecated |

Primary key: `(project_id, iri, entity_type)`.

### `labels`

| Column | Type | Meaning |
|---|---|---|
| `project_id` | `uuid` | Project id |
| `iri` | `text` | Subject entity IRI |
| `property_iri` | `text` | Annotation property IRI (e.g. `rdfs:label`) |
| `lang` | `text` | Language tag; empty string when none |
| `value` | `text` | Literal value |

Indexes: `(project_id, iri)`, `(project_id, lower(value) text_pattern_ops)`.

### `hierarchy_edges`

| Column | Type | Meaning |
|---|---|---|
| `project_id` | `uuid` | Project id |
| `hierarchy` | `text` | One of `CLASS`, `OBJECT_PROPERTY`, `DATA_PROPERTY`, `ANNOTATION_PROPERTY` |
| `child_iri` | `text` | Child entity IRI |
| `parent_iri` | `text` | Parent entity IRI |

Primary key: `(project_id, hierarchy, child_iri, parent_iri)`. Index on `(project_id, hierarchy, parent_iri)`.

### `property_domains`

| Column | Type | Meaning |
|---|---|---|
| `project_id` | `uuid` | Project id |
| `property_iri` | `text` | Property IRI |
| `class_iri` | `text` | Declared domain class IRI |

Primary key: `(project_id, property_iri, class_iri)`.

### `property_ranges`

| Column | Type | Meaning |
|---|---|---|
| `project_id` | `uuid` | Project id |
| `property_iri` | `text` | Property IRI |
| `range_iri` | `text` | Declared range IRI |
| `range_kind` | `text` | `CLASS` or `DATATYPE` |

Primary key: `(project_id, property_iri, range_iri)`.

## Example SQL

Replace `:project_id` with the project UUID.

### All descendants of a class (recursive CTE)

```sql
with recursive descendants as (
    select child_iri as iri
    from projection.hierarchy_edges
    where project_id = :project_id
      and hierarchy = 'CLASS'
      and parent_iri = 'http://example.org/ontology#HandlingUnit'
    union
    select e.child_iri
    from projection.hierarchy_edges e
    join descendants d on e.parent_iri = d.iri
    where e.project_id = :project_id
      and e.hierarchy = 'CLASS'
)
select iri from descendants;
```

### Entities whose label starts with a prefix

```sql
select distinct l.iri, l.lang, l.value
from projection.labels l
where l.project_id = :project_id
  and lower(l.value) like lower('Pal%');
```

### Domains and ranges of a property

```sql
select 'domain' as kind, class_iri as iri, null::text as range_kind
from projection.property_domains
where project_id = :project_id
  and property_iri = 'http://example.org/ontology#storedAt'
union all
select 'range', range_iri, range_kind
from projection.property_ranges
where project_id = :project_id
  and property_iri = 'http://example.org/ontology#storedAt';
```

### Deprecated entities in a project

```sql
select iri, entity_type
from projection.entities
where project_id = :project_id
  and deprecated = true
order by entity_type, iri;
```

## Read-only database role (DBA script)

The application does **not** create this role in Flyway. A DBA can run:

```sql
create role wp_projection_reader login password 'CHANGE_ME';
grant usage on schema projection to wp_projection_reader;
grant select on all tables in schema projection to wp_projection_reader;
alter default privileges in schema projection
    grant select on tables to wp_projection_reader;
```

Replace `CHANGE_ME` with a secret managed outside the repository.

## Rebuild

1. **Admin API** (system admin only): `POST /api/admin/projections/{projectId}/rebuild` (`rebuildProjection`). Synchronous; returns the same body as status.
2. **Status**: `GET /api/admin/projections/{projectId}` (`getProjectionStatus`) returns `revNo`, `head`, `lag`, `rebuiltAt`, `updatedAt`. When no projection row exists, `revNo`, `lag`, `rebuiltAt`, and `updatedAt` are null.
3. **Manual reset**: delete the project's `projection.project_state` row (and optionally its data rows). The next outbox revision for that project triggers a full rebuild when projection is enabled.

Both admin endpoints require application action `EDIT_APPLICATION_SETTINGS` (only `SYSTEM_ADMIN`). Status may be read for trashed projects; rebuild returns `409 PROJECT_TRASHED`.
