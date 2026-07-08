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
