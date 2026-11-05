CREATE TABLE knowledge_base (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_user_id BIGINT NOT NULL,
 name VARCHAR(160) NOT NULL, description VARCHAR(1000), status VARCHAR(20) NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY (owner_user_id) REFERENCES sys_user(id)
);
CREATE INDEX idx_kb_owner ON knowledge_base(owner_user_id,id);
CREATE TABLE knowledge_document (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, kb_id BIGINT NOT NULL,
 original_filename VARCHAR(255) NOT NULL, mime_type VARCHAR(120) NOT NULL,
 sha256 CHAR(64) NOT NULL, size_bytes BIGINT NOT NULL, storage_key VARCHAR(255) NOT NULL,
 status VARCHAR(20) NOT NULL, chunk_count INT, embedding_version VARCHAR(100),
 index_version INT NOT NULL DEFAULT 1, error_code VARCHAR(80),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY (kb_id) REFERENCES knowledge_base(id), UNIQUE(kb_id,sha256)
);
CREATE INDEX idx_doc_kb_status ON knowledge_document(kb_id,status,id);
