package games.beiming.website.auth.vo;

public class AuthTestConnectionVO {

    private String service;
    private String status;
    private String message;

    public AuthTestConnectionVO() {
    }

    public AuthTestConnectionVO(String service, String status, String message) {
        this.service = service;
        this.status = status;
        this.message = message;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
