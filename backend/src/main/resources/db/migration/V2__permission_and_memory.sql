create table if not exists session_permission_modes (
  session_id varchar(64) primary key,
  mode varchar(32) not null,
  updated_at datetime not null,
  constraint chk_session_permission_modes_mode
    check (mode in ('DEFAULT', 'EXPLORE', 'ACCEPT_EDITS', 'DONT_ASK', 'BYPASS'))
);

create table if not exists user_memories (
  id bigint primary key auto_increment,
  user_id bigint not null,
  memory_date date null,
  content mediumtext not null,
  updated_at datetime not null,
  index idx_user_memories_user_date (user_id, memory_date),
  index idx_user_memories_updated_at (updated_at)
);
