alter table chat_messages
  modify created_at datetime(6) not null,
  modify updated_at datetime(6) not null;
