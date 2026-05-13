create table if not exists beiming_users (
  id varchar(64) primary key,
  name varchar(120) not null,
  email varchar(255) not null unique,
  password_hash text not null,
  password_salt text not null,
  role varchar(32) not null,
  status varchar(32) not null,
  created_at bigint not null,
  updated_at bigint not null,
  last_login_at bigint not null
);

create table if not exists beiming_sessions (
  token_hash varchar(128) primary key,
  user_id varchar(64) not null references beiming_users(id) on delete cascade,
  created_at bigint not null,
  expires_at bigint not null
);

create index if not exists idx_beiming_sessions_user_id on beiming_sessions(user_id);
create index if not exists idx_beiming_sessions_expires_at on beiming_sessions(expires_at);

create table if not exists beiming_invite_codes (
  id varchar(64) primary key,
  code varchar(64) not null unique,
  type varchar(32) not null,
  role varchar(32) not null,
  status varchar(32) not null,
  max_uses int not null,
  used_count int not null,
  expires_at bigint not null,
  created_by varchar(64) not null,
  created_at bigint not null,
  updated_at bigint not null
);

create table if not exists beiming_invite_code_usages (
  id varchar(64) primary key,
  invite_code_id varchar(64) not null,
  user_id varchar(64) not null,
  used_at bigint not null
);

create index if not exists idx_beiming_invite_codes_code on beiming_invite_codes(code);
create index if not exists idx_beiming_invite_code_usages_invite_code_id on beiming_invite_code_usages(invite_code_id);
