create table if not exists chat_messages (
  id varchar(64) primary key,
  session_id varchar(64) not null,
  user_id bigint not null,
  role varchar(32) not null,
  content text not null,
  events_json json not null,
  loading boolean not null default false,
  created_at datetime not null,
  updated_at datetime not null,
  index idx_chat_messages_session_created (user_id, session_id, created_at),
  index idx_chat_messages_session_updated (user_id, session_id, updated_at)
);
