create index if not exists idx_notification_events_recipient_created_at
  on beiming_notification_events(recipient_user_id, created_at desc);

create index if not exists idx_notification_deliveries_status_channel_created_at
  on beiming_notification_deliveries(status, channel, created_at desc);

create index if not exists idx_notification_deliveries_recipient_created_at
  on beiming_notification_deliveries(recipient_user_id, created_at desc);
