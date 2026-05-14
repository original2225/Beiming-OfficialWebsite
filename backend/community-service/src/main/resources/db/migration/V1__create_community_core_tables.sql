create table if not exists beiming_community_boards (
  id varchar(64) primary key,
  slug varchar(80) not null unique,
  name varchar(120) not null,
  description text not null,
  visibility varchar(32) not null,
  posting_role varchar(32) not null,
  sort_order int not null,
  created_at bigint not null,
  updated_at bigint not null
);

create table if not exists beiming_community_posts (
  id varchar(64) primary key,
  board_id varchar(64) not null,
  author_user_id varchar(64) not null,
  author_display_name varchar(120) not null,
  author_avatar_url text not null,
  author_minecraft_id varchar(64) not null,
  title varchar(160) not null,
  content text not null,
  status varchar(32) not null,
  visibility varchar(32) not null,
  review_status varchar(32) not null,
  pinned boolean not null,
  locked boolean not null,
  view_count bigint not null,
  comment_count bigint not null,
  like_count bigint not null,
  favorite_count bigint not null,
  has_poll boolean not null,
  created_at bigint not null,
  updated_at bigint not null,
  published_at bigint not null,
  deleted_at bigint not null,
  last_moderated_by varchar(64) not null,
  moderation_note text not null
);

create table if not exists beiming_community_comments (
  id varchar(64) primary key,
  post_id varchar(64) not null,
  parent_comment_id varchar(64) not null,
  author_user_id varchar(64) not null,
  author_display_name varchar(120) not null,
  author_avatar_url text not null,
  author_minecraft_id varchar(64) not null,
  content text not null,
  status varchar(32) not null,
  like_count bigint not null,
  created_at bigint not null,
  updated_at bigint not null,
  deleted_at bigint not null,
  last_moderated_by varchar(64) not null,
  moderation_note text not null
);

create table if not exists beiming_community_post_reactions (
  id varchar(64) primary key,
  post_id varchar(64) not null,
  user_id varchar(64) not null,
  reaction_type varchar(32) not null,
  created_at bigint not null,
  unique(post_id, user_id, reaction_type)
);

create table if not exists beiming_community_comment_reactions (
  id varchar(64) primary key,
  comment_id varchar(64) not null,
  user_id varchar(64) not null,
  reaction_type varchar(32) not null,
  created_at bigint not null,
  unique(comment_id, user_id, reaction_type)
);

create table if not exists beiming_community_post_favorites (
  id varchar(64) primary key,
  post_id varchar(64) not null,
  user_id varchar(64) not null,
  created_at bigint not null,
  unique(post_id, user_id)
);

create table if not exists beiming_community_reports (
  id varchar(64) primary key,
  target_type varchar(32) not null,
  target_id varchar(64) not null,
  reporter_user_id varchar(64) not null,
  reporter_display_name varchar(120) not null,
  reason varchar(32) not null,
  detail text not null,
  status varchar(32) not null,
  reviewer_user_id varchar(64) not null,
  review_note text not null,
  created_at bigint not null,
  updated_at bigint not null,
  resolved_at bigint not null
);

create table if not exists beiming_community_polls (
  id varchar(64) primary key,
  post_id varchar(64) not null unique,
  question varchar(240) not null,
  vote_mode varchar(32) not null,
  result_visibility varchar(32) not null,
  closes_at bigint not null,
  closed boolean not null,
  created_at bigint not null,
  updated_at bigint not null
);

create table if not exists beiming_community_poll_options (
  id varchar(64) primary key,
  poll_id varchar(64) not null,
  option_text varchar(160) not null,
  sort_order int not null
);

create table if not exists beiming_community_poll_votes (
  id varchar(64) primary key,
  poll_id varchar(64) not null,
  option_id varchar(64) not null,
  user_id varchar(64) not null,
  created_at bigint not null,
  unique(poll_id, option_id, user_id)
);

create index if not exists idx_beiming_community_boards_visibility on beiming_community_boards(visibility, sort_order);
create index if not exists idx_beiming_community_posts_board_status on beiming_community_posts(board_id, status, visibility, published_at);
create index if not exists idx_beiming_community_posts_author on beiming_community_posts(author_user_id, created_at);
create index if not exists idx_beiming_community_comments_post on beiming_community_comments(post_id, created_at);
create index if not exists idx_beiming_community_reports_target on beiming_community_reports(target_type, target_id, status);

insert into beiming_community_boards (id, slug, name, description, visibility, posting_role, sort_order, created_at, updated_at)
select 'board-announcements', 'announcements', '公告区', '官方公告和规则', 'PUBLIC', 'ADMIN', 10, 0, 0
where not exists (select 1 from beiming_community_boards where slug = 'announcements');

insert into beiming_community_boards (id, slug, name, description, visibility, posting_role, sort_order, created_at, updated_at)
select 'board-modpacks', 'modpacks', '整合包区', '整合包发布和讨论', 'PUBLIC', 'MEMBER', 20, 0, 0
where not exists (select 1 from beiming_community_boards where slug = 'modpacks');

insert into beiming_community_boards (id, slug, name, description, visibility, posting_role, sort_order, created_at, updated_at)
select 'board-vanilla', 'vanilla', '原版生存区', '原版玩法和服务器生活', 'PUBLIC', 'MEMBER', 30, 0, 0
where not exists (select 1 from beiming_community_boards where slug = 'vanilla');

insert into beiming_community_boards (id, slug, name, description, visibility, posting_role, sort_order, created_at, updated_at)
select 'board-building', 'building', '建筑区', '建筑作品和施工记录', 'PUBLIC', 'MEMBER', 40, 0, 0
where not exists (select 1 from beiming_community_boards where slug = 'building');

insert into beiming_community_boards (id, slug, name, description, visibility, posting_role, sort_order, created_at, updated_at)
select 'board-redstone', 'redstone', '红石技术区', '电路设计和机制研究', 'PUBLIC', 'MEMBER', 50, 0, 0
where not exists (select 1 from beiming_community_boards where slug = 'redstone');

insert into beiming_community_boards (id, slug, name, description, visibility, posting_role, sort_order, created_at, updated_at)
select 'board-resources', 'resources', '资源发布区', '地图、材质、工具和教程', 'PUBLIC', 'MEMBER', 60, 0, 0
where not exists (select 1 from beiming_community_boards where slug = 'resources');

insert into beiming_community_boards (id, slug, name, description, visibility, posting_role, sort_order, created_at, updated_at)
select 'board-help', 'help', '求助答疑区', '问题求助和经验交流', 'PUBLIC', 'MEMBER', 70, 0, 0
where not exists (select 1 from beiming_community_boards where slug = 'help');

insert into beiming_community_boards (id, slug, name, description, visibility, posting_role, sort_order, created_at, updated_at)
select 'board-events', 'events', '活动赛事区', '社区活动和比赛', 'PUBLIC', 'MEMBER', 80, 0, 0
where not exists (select 1 from beiming_community_boards where slug = 'events');

insert into beiming_community_boards (id, slug, name, description, visibility, posting_role, sort_order, created_at, updated_at)
select 'board-archive', 'archive', '归档区', '历史内容归档', 'HIDDEN', 'ADMIN', 90, 0, 0
where not exists (select 1 from beiming_community_boards where slug = 'archive');
