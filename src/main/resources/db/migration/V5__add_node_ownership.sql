
-- V5: 增加用户与服务器归属关系
-- MySQL 8.0

-- 1. 服务器表增加归属用户字段
ALTER TABLE ops_node
    ADD COLUMN owner_user_id BIGINT NULL;

-- 2. 注册令牌增加创建人字段
ALTER TABLE ops_agent_enroll_token
    ADD COLUMN issued_by_user_id BIGINT NULL;

-- 3. 服务器归属外键
ALTER TABLE ops_node
    ADD CONSTRAINT fk_ops_node_owner
        FOREIGN KEY (owner_user_id)
            REFERENCES sys_user(id);

-- 4. 注册令牌创建人外键
ALTER TABLE ops_agent_enroll_token
    ADD CONSTRAINT fk_enroll_token_issuer
        FOREIGN KEY (issued_by_user_id)
            REFERENCES sys_user(id);

-- 5. 创建查询索引
CREATE INDEX idx_ops_node_owner
    ON ops_node(owner_user_id);

CREATE INDEX idx_enroll_token_issuer
    ON ops_agent_enroll_token(issued_by_user_id);
