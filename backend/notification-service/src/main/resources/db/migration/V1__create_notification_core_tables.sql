create table if not exists beiming_notification_events (
  id varchar(64) primary key,
  event_key varchar(160) not null unique,
  event_type varchar(64) not null,
  source_service varchar(64) not null,
  source_id varchar(160) not null,
  actor_user_id varchar(64) not null,
  actor_display_name varchar(120) not null,
  actor_avatar_url text not null,
  recipient_user_id varchar(64) not null,
  target_type varchar(64) not null,
  target_id varchar(160) not null,
  title varchar(160) not null,
  body text not null,
  action_url text not null,
  payload_json text not null,
  created_at bigint not null
);

create table if not exists beiming_notifications (
  id varchar(64) primary key,
  event_id varchar(64) not null,
  recipient_user_id varchar(64) not null,
  type varchar(64) not null,
  status varchar(32) not null,
  title varchar(160) not null,
  body text not null,
  actor_user_id varchar(64) not null,
  actor_display_name varchar(120) not null,
  actor_avatar_url text not null,
  target_type varchar(64) not null,
  target_id varchar(160) not null,
  action_url text not null,
  payload_json text not null,
  created_at bigint not null,
  read_at bigint not null,
  archived_at bigint not null,
  constraint fk_notification_event foreign key (event_id) references beiming_notification_events(id)
);

create table if not exists beiming_notification_deliveries (
  id varchar(64) primary key,
  notification_id varchar(64) not null,
  recipient_user_id varchar(64) not null,
  channel varchar(32) not null,
  status varchar(32) not null,
  attempt_count int not null,
  last_error text not null,
  created_at bigint not null,
  updated_at bigint not null,
  constraint fk_delivery_notification foreign key (notification_id) references beiming_notifications(id)
);

create index if not exists idx_notification_events_type_created_at
  on beiming_notification_events(event_type, created_at desc);

create index if not exists idx_notifications_recipient_status_created_at
  on beiming_notifications(recipient_user_id, status, created_at desc);

create index if not exists idx_notifications_recipient_created_at
  on beiming_notifications(recipient_user_id, created_at desc);

create index if not exists idx_deliveries_notification_id
  on beiming_notification_deliveries(notification_id);
