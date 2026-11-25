-- Soft-delete marker for users (P6-29). The row stays so foreign keys and revision history keep the account id.
alter table users add column deleted_at timestamptz;
