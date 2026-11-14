-- Derived ontology projection tables (ADR-013). Rebuildable from the revision log.

create schema projection;

create table projection.project_state (
    project_id  uuid primary key,
    rev_no      bigint not null,
    rebuilt_at  timestamptz not null,
    updated_at  timestamptz not null
);

create table projection.entities (
    project_id  uuid not null,
    iri         text not null,
    entity_type text not null
        check (entity_type in (
            'CLASS',
            'OBJECT_PROPERTY',
            'DATA_PROPERTY',
            'ANNOTATION_PROPERTY',
            'NAMED_INDIVIDUAL',
            'DATATYPE'
        )),
    deprecated  boolean not null,
    primary key (project_id, iri, entity_type)
);

create table projection.labels (
    project_id   uuid not null,
    iri          text not null,
    property_iri text not null,
    lang         text not null default '',
    value        text not null
);
create index labels_project_iri on projection.labels (project_id, iri);
create index labels_project_value_lower on projection.labels (project_id, lower(value) text_pattern_ops);

create table projection.hierarchy_edges (
    project_id uuid not null,
    hierarchy  text not null
        check (hierarchy in (
            'CLASS',
            'OBJECT_PROPERTY',
            'DATA_PROPERTY',
            'ANNOTATION_PROPERTY'
        )),
    child_iri  text not null,
    parent_iri text not null,
    primary key (project_id, hierarchy, child_iri, parent_iri)
);
create index hierarchy_edges_parent on projection.hierarchy_edges (project_id, hierarchy, parent_iri);

create table projection.property_domains (
    project_id   uuid not null,
    property_iri text not null,
    class_iri    text not null,
    primary key (project_id, property_iri, class_iri)
);

create table projection.property_ranges (
    project_id   uuid not null,
    property_iri text not null,
    range_iri    text not null,
    range_kind   text not null
        check (range_kind in ('CLASS', 'DATATYPE')),
    primary key (project_id, property_iri, range_iri)
);
