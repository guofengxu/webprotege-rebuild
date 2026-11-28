-- Project form descriptors and selectors (BS-16 / P6-66).

create table project_form (
    project_id uuid not null,
    form_id    uuid not null,
    ordinal    int not null,
    descriptor jsonb not null,
    primary key (project_id, form_id)
);
create index project_form_project_ordinal on project_form (project_id, ordinal);

create table project_form_selector (
    project_id uuid not null,
    form_id    uuid not null,
    criteria   jsonb not null,
    primary key (project_id, form_id)
);
