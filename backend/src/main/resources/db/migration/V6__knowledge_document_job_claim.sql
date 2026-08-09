alter table knowledge_document_jobs
  add column if not exists claimed_until datetime null;
