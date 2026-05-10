package games.beiming.website.auth.dto;

import games.beiming.website.common.security.enums.PermissionLevel;

import java.time.LocalDateTime;

public class CreateInviteCodeRequestDTO {

    private String code;
    private PermissionLevel permissionLevel;
    private Integer maxUses;
    private LocalDateTime expiresAt;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public PermissionLevel getPermissionLevel() {
        return permissionLevel;
    }

    public void setPermissionLevel(PermissionLevel permissionLevel) {
        this.permissionLevel = permissionLevel;
    }

    public Integer getMaxUses() {
        return maxUses;
    }

    public void setMaxUses(Integer maxUses) {
        this.maxUses = maxUses;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
