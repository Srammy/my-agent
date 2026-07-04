create table if not exists users (
  id bigint primary key auto_increment,
  username varchar(64) not null,
  password_hash varchar(255) not null,
  display_name varchar(64),
  role varchar(32) not null default 'USER',
  created_at datetime not null,
  updated_at datetime not null,
  unique key uk_users_username (username)
);

create table if not exists chat_sessions (
  id varchar(64) primary key,
  user_id bigint not null,
  title varchar(120) not null,
  created_at datetime not null,
  updated_at datetime not null,
  index idx_chat_sessions_user_id (user_id)
);

create table if not exists skills (
  id bigint primary key auto_increment,
  owner_type varchar(16) not null,
  owner_user_id bigint null,
  owner_user_id_norm bigint generated always as (coalesce(owner_user_id, 0)) stored,
  name varchar(100) not null,
  description varchar(255) not null,
  enabled tinyint(1) not null default 1,
  created_at datetime not null,
  updated_at datetime not null,
  unique key uk_skill_owner_name (owner_type, owner_user_id_norm, name)
);

create table if not exists skill_files (
  id bigint primary key auto_increment,
  skill_id bigint not null,
  path varchar(500) not null,
  content mediumtext null,
  content_type varchar(64) not null default 'text/markdown',
  executable tinyint(1) not null default 0,
  created_at datetime not null,
  updated_at datetime not null,
  unique key uk_skill_file_path (skill_id, path)
);

create table if not exists user_skill_settings (
  id bigint primary key auto_increment,
  user_id bigint not null,
  skill_id bigint not null,
  enabled tinyint(1) not null default 1,
  unique key uk_user_skill_setting (user_id, skill_id)
);

create table if not exists agent_evolution_proposals (
  id bigint primary key auto_increment,
  user_id bigint not null,
  session_id varchar(64) null,
  type varchar(32) not null,
  title varchar(200) not null,
  summary varchar(1000) null,
  content mediumtext not null,
  status varchar(32) not null,
  created_at datetime not null,
  updated_at datetime not null,
  applied_at datetime null
);
