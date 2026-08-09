alter table knowledge_document_jobs
  add column if not exists claim_token varchar(64) null;
