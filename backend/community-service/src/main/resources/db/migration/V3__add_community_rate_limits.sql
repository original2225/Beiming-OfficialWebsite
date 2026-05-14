create table if not exists beiming_community_rate_limits (
  scope varchar(160) not null,
  bucket_start bigint not null,
  request_count int not null,
  updated_at bigint not null,
  primary key (scope, bucket_start)
);

create index if not exists idx_beiming_community_rate_limits_updated
  on beiming_community_rate_limits(updated_at);

create index if not exists idx_beiming_community_comments_post_page
  on beiming_community_comments(post_id, status, created_at, id);
