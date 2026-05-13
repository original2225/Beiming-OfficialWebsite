create table if not exists beiming_cloud_drives (
  id varchar(64) primary key,
  user_id varchar(64) not null references beiming_users(id) on delete cascade,
  provider varchar(32) not null,
  display_name varchar(160) not null,
  account_name varchar(255) not null,
  drive_id text not null,
  root_item_id text not null,
  access_token text not null,
  refresh_token text not null,
  token_expires_at bigint not null,
  auth_mode varchar(40) not null default 'beiming',
  created_at bigint not null,
  updated_at bigint not null,
  unique(user_id, provider, drive_id)
);

create index if not exists idx_beiming_cloud_drives_user_id on beiming_cloud_drives(user_id);

create table if not exists beiming_cloud_oauth_states (
  state varchar(160) primary key,
  user_id varchar(64) not null references beiming_users(id) on delete cascade,
  created_at bigint not null,
  expires_at bigint not null
);

create index if not exists idx_beiming_cloud_oauth_states_user_id on beiming_cloud_oauth_states(user_id);

create table if not exists beiming_cloud_oauth_configs (
  id varchar(64) primary key,
  user_id varchar(64) not null references beiming_users(id) on delete cascade,
  provider varchar(32) not null,
  client_id text not null,
  client_secret text not null,
  redirect_uri text not null,
  cdn_host text not null default '',
  created_at bigint not null,
  updated_at bigint not null,
  unique(user_id, provider)
);

create index if not exists idx_beiming_cloud_oauth_configs_user_id on beiming_cloud_oauth_configs(user_id);
