create table if not exists session_permission_modes (
  session_id varchar(64) primary key,
  mode varchar(32) not null,
  updated_at datetime not null,
  constraint chk_session_permission_modes_mode
    check (mode in ('DEFAULT', 'EXPLORE', 'ACCEPT_EDITS', 'DONT_ASK', 'BYPASS'))
);
