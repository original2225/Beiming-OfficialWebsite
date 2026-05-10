package games.beiming.website.auth.vo;

import games.beiming.website.auth.enums.InviteCodeStatus;
import games.beiming.website.common.security.enums.PermissionLevel;

import java.time.LocalDateTime;

public class InviteCodeVO {

    private Long id;
    private String code;
    private PermissionLevel permissionLevel;
    private Integer maxUses;
    private Integer usedCount;
    private LocalDateTime expiresAt;
    private InviteCodeStatus status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public Integer getUsedCount() {
        return usedCount;
    }

    public void setUsedCount(Integer usedCount) {
        this.usedCount = usedCount;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public InviteCodeStatus getStatus() {
        return status;
    }

    public void setStatus(InviteCodeStatus status) {
        this.status = status;
    }
}
