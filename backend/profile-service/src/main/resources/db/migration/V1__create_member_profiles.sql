create table if not exists beiming_member_profiles (
  id varchar(64) primary key,
  user_id varchar(64) not null unique,
  display_name varchar(120) not null,
  bio text not null,
  avatar_url text not null,
  minecraft_id varchar(64) not null,
  minecraft_id_key varchar(64) not null unique,
  minecraft_uuid varchar(64) not null,
  member_group varchar(32) not null,
  member_status varchar(32) not null,
  visibility varchar(32) not null,
  joined_at bigint not null,
  created_at bigint not null,
  updated_at bigint not null,
  admin_note text not null,
  featured boolean not null
);

create index if not exists idx_beiming_member_profiles_public on beiming_member_profiles(visibility, member_status);
create index if not exists idx_beiming_member_profiles_user_id on beiming_member_profiles(user_id);
