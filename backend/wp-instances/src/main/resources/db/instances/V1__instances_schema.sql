-- Instance store schema (ADR-013). Validity intervals are half-open [valid_from, valid_to).

create table instances (
    iri           text not null,
    class_iri     text not null,
    attributes    jsonb not null default '{}',
    valid_from    timestamptz not null,
    valid_to      timestamptz,
    source        text not null,
    ontology_rev  bigint not null,
    primary key (iri, valid_from),
    check (valid_to is null or valid_to > valid_from)
);

-- At most one current version per instance.
create unique index instances_current_iri
    on instances (iri)
    where valid_to is null;

create index instances_current_class
    on instances (class_iri)
    where valid_to is null;

create table links (
    subject_iri   text not null,
    property_iri  text not null,
    object_iri    text not null,
    valid_from    timestamptz not null,
    valid_to      timestamptz,
    primary key (subject_iri, property_iri, object_iri, valid_from),
    check (valid_to is null or valid_to > valid_from)
);

-- At most one current version of a given relationship.
create unique index links_current_triple
    on links (subject_iri, property_iri, object_iri)
    where valid_to is null;

create index links_current_subject_property
    on links (subject_iri, property_iri)
    where valid_to is null;

-- Reverse lookup for inbound traversal.
create index links_current_object_property
    on links (object_iri, property_iri)
    where valid_to is null;

-- Historical as-of queries.
create index links_history_subject_property
    on links (subject_iri, property_iri, valid_from);

create index links_history_object_property
    on links (object_iri, property_iri, valid_from);
