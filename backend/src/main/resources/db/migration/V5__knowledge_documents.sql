create table if not exists knowledge_documents (
  id varchar(64) primary key,
  user_id bigint not null,
  original_filename varchar(255) not null,
  content_type varchar(128),
  size_bytes bigint not null,
  storage_key varchar(512) not null,
  status varchar(32) not null default 'PROCESSING',
  parent_count int not null default 0,
  child_count int not null default 0,
  error_message varchar(2000),
  created_at datetime not null,
  updated_at datetime not null,
  constraint ck_knowledge_documents_status
    check (status in ('PROCESSING', 'READY', 'FAILED')),
  index idx_knowledge_documents_user_id (user_id),
  index idx_knowledge_documents_user_status (user_id, status)
);

create table if not exists knowledge_document_jobs (
  id varchar(64) primary key,
  document_id varchar(64) not null,
  user_id bigint not null,
  status varchar(32) not null default 'PENDING',
  attempts int not null default 0,
  last_error varchar(2000),
  created_at datetime not null,
  updated_at datetime not null,
  constraint uk_knowledge_document_jobs_document unique (document_id),
  constraint ck_knowledge_document_jobs_status
    check (status in ('PENDING', 'SENT', 'FAILED')),
  index idx_knowledge_document_jobs_user_id (user_id),
  index idx_knowledge_document_jobs_user_status (user_id, status)
);

alter table chat_sessions
  add column mode varchar(32) not null default 'NORMAL';

alter table chat_sessions
  add constraint ck_chat_sessions_mode check (mode in ('NORMAL', 'KNOWLEDGE'));
