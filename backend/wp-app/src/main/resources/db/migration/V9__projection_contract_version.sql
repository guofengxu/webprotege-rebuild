-- Contract version for projection consumers (instance store). Existing rows get 1 via default.
alter table projection.project_state
    add column contract_version integer not null default 1;
