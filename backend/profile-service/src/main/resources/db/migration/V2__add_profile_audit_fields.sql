alter table beiming_member_profiles
  add column if not exists created_by varchar(64) not null default '';

alter table beiming_member_profiles
  add column if not exists updated_by varchar(64) not null default '';

create index if not exists idx_beiming_member_profiles_updated_by on beiming_member_profiles(updated_by);
