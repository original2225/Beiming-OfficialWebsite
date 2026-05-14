create index if not exists idx_beiming_community_posts_public_feed
  on beiming_community_posts(status, review_status, visibility, pinned, published_at desc, created_at desc);

create index if not exists idx_beiming_community_posts_hot_feed
  on beiming_community_posts(status, review_status, visibility, pinned, like_count desc, comment_count desc, published_at desc);

create index if not exists idx_beiming_community_post_favorites_user_created
  on beiming_community_post_favorites(user_id, created_at desc);

create index if not exists idx_beiming_community_post_reactions_user
  on beiming_community_post_reactions(user_id, post_id, reaction_type);

create index if not exists idx_beiming_community_comment_reactions_user
  on beiming_community_comment_reactions(user_id, comment_id, reaction_type);

create index if not exists idx_beiming_community_poll_options_poll_sort
  on beiming_community_poll_options(poll_id, sort_order);

create index if not exists idx_beiming_community_poll_votes_poll_user
  on beiming_community_poll_votes(poll_id, user_id);

create index if not exists idx_beiming_community_poll_votes_option
  on beiming_community_poll_votes(option_id);

create index if not exists idx_beiming_community_reports_status_created
  on beiming_community_reports(status, created_at desc);
