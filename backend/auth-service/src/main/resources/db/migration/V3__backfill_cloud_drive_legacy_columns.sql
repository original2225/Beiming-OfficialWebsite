alter table beiming_cloud_drives
  add column if not exists auth_mode varchar(40) not null default 'beiming';

alter table beiming_cloud_oauth_configs
  add column if not exists cdn_host text not null default '';
