create table if not exists beiming_community_audit_logs (
  id varchar(80) primary key,
  actor_user_id varchar(64) not null,
  actor_display_name varchar(120) not null,
  action varchar(80) not null,
  target_type varchar(32) not null,
  target_id varchar(80) not null,
  detail text not null,
  created_at bigint not null
);

create index if not exists idx_beiming_community_audit_logs_created
  on beiming_community_audit_logs(created_at desc);

create unique index if not exists uq_beiming_community_reports_open_reason
  on beiming_community_reports(target_type, target_id, reporter_user_id, reason, status, resolved_at);
