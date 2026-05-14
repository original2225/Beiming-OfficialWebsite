create table if not exists beiming_cloud_shortcuts (
  id varchar(64) primary key,
  user_id varchar(64) not null references beiming_users(id) on delete cascade,
  target_drive_id varchar(64) not null references beiming_cloud_drives(id) on delete cascade,
  source_drive_id text not null,
  source_item_id text not null,
  name varchar(255) not null,
  created_at bigint not null,
  updated_at bigint not null,
  unique(user_id, target_drive_id, source_drive_id, source_item_id)
);

create index if not exists idx_beiming_cloud_shortcuts_target on beiming_cloud_shortcuts(user_id, target_drive_id);

delete from beiming_cloud_drives where auth_mode = 'shared-shortcut-fallback';
