CREATE TABLE IF NOT EXISTS auth_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(120) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    minecraft_id VARCHAR(64) NOT NULL,
    permission_level VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_user_username (username),
    UNIQUE KEY uk_auth_user_minecraft_id (minecraft_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS auth_invite_code (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    permission_level VARCHAR(20) NOT NULL,
    max_uses INT NOT NULL,
    used_count INT NOT NULL,
    expires_at DATETIME NULL,
    status VARCHAR(20) NOT NULL,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_invite_code_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS auth_invite_code_usage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    invite_code_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    used_at DATETIME NOT NULL,
    used_ip VARCHAR(64) NULL,
    PRIMARY KEY (id),
    KEY idx_auth_invite_code_usage_code_id (invite_code_id),
    KEY idx_auth_invite_code_usage_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
