package dev.beiming.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class CloudDriveService {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final String GRAPH_ROOT = "https://graph.microsoft.com/v1.0";
  private static final String MICROSOFT_LOGIN_ROOT = "https://login.microsoftonline.com";
  private static final String SCOPES = "offline_access Files.ReadWrite.All User.Read";
  private static final String AUTH_MODE_BEIMING = "beiming";
  private static final long OAUTH_STATE_TTL_MS = 10 * 60 * 1000L;

  private final AuthService auth;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final HttpClient httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(12))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .version(HttpClient.Version.HTTP_2)
    .build();
  private final String clientId;
  private final String clientSecret;
  private final String redirectUri;
  private final String tenant;
  private final String frontendOrigin;

  private final RowMapper<CloudDriveRecord> driveMapper = (rs, rowNum) -> new CloudDriveRecord(
    rs.getString("id"),
    rs.getString("user_id"),
    rs.getString("provider"),
    rs.getString("display_name"),
    rs.getString("account_name"),
    rs.getString("drive_id"),
    rs.getString("root_item_id"),
    rs.getString("access_token"),
    rs.getString("refresh_token"),
    rs.getLong("token_expires_at"),
    rs.getString("auth_mode"),
    rs.getLong("created_at"),
    rs.getLong("updated_at")
  ).normalized();

  CloudDriveService(
    AuthService auth,
    JdbcTemplate jdbc,
    ObjectMapper mapper,
    @Value("${beiming.onedrive.client-id}") String clientId,
    @Value("${beiming.onedrive.client-secret}") String clientSecret,
    @Value("${beiming.onedrive.redirect-uri}") String redirectUri,
    @Value("${beiming.onedrive.tenant}") String tenant,
    @Value("${beiming.frontend-origin}") String frontendOrigin
  ) {
    this.auth = auth;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.clientId = string(clientId);
    this.clientSecret = string(clientSecret);
    this.redirectUri = string(redirectUri);
    this.tenant = normalizeTenant(tenant);
    this.frontendOrigin = trimTrailingSlash(frontendOrigin);
  }

  synchronized Map<String, Object> status(String token) {
    var user = auth.requireUser(token);
    var config = userConfig(user.id());
    var drives = jdbc.query("select * from beiming_cloud_drives where user_id = ? order by created_at asc", driveMapper, user.id())
      .stream()
      .map(this::publicDrive)
      .toList();
    return Map.of(
      "configured", isPlatformConfigured() || isConfigured(config),
      "platformConfigured", isPlatformConfigured(),
      "authUrl", "",
      "config", Map.of(
        "clientId", mask(effectiveClientId(config)),
        "redirectUri", effectiveRedirectUri(config),
        "cdnHost", effectiveCdnHost(config),
        "hasSecret", !effectiveClientSecret(config).isBlank()
      ),
      "drives", drives
    );
  }

  synchronized Map<String, Object> saveConfig(String token, Map<String, Object> body) {
    var user = auth.requireUser(token);
    var previous = userConfig(user.id());
    var nextClientId = firstNonBlank(string(body.get("clientId")), string(previous.get("client_id")), clientId);
    var nextClientSecret = firstNonBlank(string(body.get("clientSecret")), string(previous.get("client_secret")));
    var nextRedirectUri = firstNonBlank(string(body.get("redirectUri")), string(previous.get("redirect_uri")), redirectUri);
    var nextCdnHost = normalizeCdnHosts(string(body.get("cdnHost")));
    var hasOAuthFields = body.containsKey("clientId") || body.containsKey("clientSecret") || body.containsKey("redirectUri");
    if (hasOAuthFields || previous.isEmpty() || !isPlatformConfigured()) {
      if (nextClientId.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "Client ID 不能为空");
      if (nextClientSecret.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "Client Secret 不能为空");
      if (nextRedirectUri.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "Redirect URI 不能为空");
    }
    var timestamp = now();
    var exists = !jdbc.queryForList("select id from beiming_cloud_oauth_configs where user_id = ? and provider = ?", user.id(), "onedrive").isEmpty();
    if (exists) {
      jdbc.update(
        """
          update beiming_cloud_oauth_configs
          set client_id = ?, client_secret = ?, redirect_uri = ?, cdn_host = ?, updated_at = ?
          where user_id = ? and provider = ?
        """,
        nextClientId, nextClientSecret, nextRedirectUri, nextCdnHost, timestamp, user.id(), "onedrive"
      );
    } else {
      jdbc.update(
        """
          insert into beiming_cloud_oauth_configs
          (id, user_id, provider, client_id, client_secret, redirect_uri, cdn_host, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        "oauth-" + UUID.randomUUID().toString().substring(0, 8),
        user.id(),
        "onedrive",
        nextClientId,
        nextClientSecret,
        nextRedirectUri,
        nextCdnHost,
        timestamp,
        timestamp
      );
    }
    return status(token);
  }

  synchronized Map<String, Object> startAuth(String token) {
    var user = auth.requireUser(token);
    var config = userConfig(user.id());
    var configuredClientId = effectiveClientId(config);
    var configuredClientSecret = effectiveClientSecret(config);
    var configuredRedirectUri = effectiveRedirectUri(config);
    if (configuredClientId.isBlank() || configuredClientSecret.isBlank() || configuredRedirectUri.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "北冥 OneDrive 应用还没配置 Client ID 和 Client Secret");
    }
    var state = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    jdbc.update(
      "insert into beiming_cloud_oauth_states (state, user_id, created_at, expires_at) values (?, ?, ?, ?)",
      state, user.id(), now(), now() + OAUTH_STATE_TTL_MS
    );
    var params = new LinkedHashMap<String, String>();
    params.put("client_id", configuredClientId);
    params.put("response_type", "code");
    params.put("redirect_uri", configuredRedirectUri);
    params.put("response_mode", "query");
    params.put("scope", SCOPES);
    params.put("state", state);
    params.put("prompt", "select_account");
    return Map.of("url", authorizeUrl() + "?" + form(params));
  }

  synchronized String completeAuth(String code, String state, String error, String errorDescription) {
    if (!error.isBlank()) return frontendCloudUrl("onedrive=error&message=" + enc(firstNonBlank(errorDescription, error)));
    if (code.isBlank() || state.isBlank()) return frontendCloudUrl("onedrive=error&message=" + enc("OneDrive 回调参数不完整"));
    var states = jdbc.queryForList("select user_id, expires_at from beiming_cloud_oauth_states where state = ?", state);
    if (states.isEmpty()) return frontendCloudUrl("onedrive=error&message=" + enc("OneDrive 授权状态已失效"));
    var row = states.get(0);
    jdbc.update("delete from beiming_cloud_oauth_states where state = ?", state);
    if (number(row.get("expires_at")) < now()) return frontendCloudUrl("onedrive=error&message=" + enc("OneDrive 授权已过期，请重新挂载"));
    try {
      var userId = string(row.get("user_id"));
      var config = userConfig(userId);
      var configuredClientId = effectiveClientId(config);
      var configuredClientSecret = effectiveClientSecret(config);
      var configuredRedirectUri = effectiveRedirectUri(config);
      if (configuredClientId.isBlank() || configuredClientSecret.isBlank() || configuredRedirectUri.isBlank()) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "北冥 OneDrive 应用还没配置 Client ID 和 Client Secret");
      }
      var tokenPayload = tokenRequest(Map.of(
        "client_id", configuredClientId,
        "client_secret", configuredClientSecret,
        "redirect_uri", configuredRedirectUri,
        "grant_type", "authorization_code",
        "code", code,
        "scope", SCOPES
      ));
      var accessToken = string(tokenPayload.get("access_token"));
      var refreshToken = string(tokenPayload.get("refresh_token"));
      if (accessToken.isBlank() || refreshToken.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "OneDrive 授权失败");
      var expiresAt = now() + Math.max(60, number(tokenPayload.get("expires_in")) - 120) * 1000L;
      var drive = upsertDrive(userId, accessToken, refreshToken, expiresAt, AUTH_MODE_BEIMING, "");
      return frontendCloudUrl("onedrive=connected&drive=" + enc(drive.id()));
    } catch (Exception exception) {
      return frontendCloudUrl("onedrive=error&message=" + enc(exception.getMessage()));
    }
  }

  synchronized Map<String, Object> connect(String token, Map<String, Object> body) {
    var user = auth.requireUser(token);
    var config = userConfig(user.id());
    requireConfigured(config);
    var code = string(body.get("code"));
    if (code.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "缺少 OneDrive 授权码");
    var configuredClientId = effectiveClientId(config);
    var configuredClientSecret = effectiveClientSecret(config);
    var configuredRedirectUri = effectiveRedirectUri(config);
    var tokenPayload = tokenRequest(Map.of(
      "client_id", configuredClientId,
      "client_secret", configuredClientSecret,
      "redirect_uri", configuredRedirectUri,
      "grant_type", "authorization_code",
      "code", code,
      "scope", SCOPES
    ));
    var accessToken = string(tokenPayload.get("access_token"));
    var refreshToken = string(tokenPayload.get("refresh_token"));
    if (accessToken.isBlank() || refreshToken.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "OneDrive 授权失败");
    var expiresAt = now() + Math.max(60, number(tokenPayload.get("expires_in")) - 120) * 1000L;
    return publicDrive(upsertDrive(user.id(), accessToken, refreshToken, expiresAt, "manual", string(body.get("displayName"))));
  }

  synchronized void disconnect(String token, String driveId) {
    var user = auth.requireUser(token);
    jdbc.update("delete from beiming_cloud_drives where id = ? and user_id = ?", driveId, user.id());
  }

  Map<String, Object> list(String token, String driveId, String itemId) {
    var user = auth.requireUser(token);
    var drive = requireFreshDrive(user.id(), driveId);
    var targetItemId = itemId == null || itemId.isBlank() || "root".equals(itemId) ? "root" : itemId;
    var itemPath = "root".equals(targetItemId)
      ? "/drives/" + enc(drive.driveId()) + "/root"
      : "/drives/" + enc(drive.driveId()) + "/items/" + enc(targetItemId);
    var children = graphGet(drive.accessToken(), itemPath + "/children?$top=200&select=id,name,folder,file,size,lastModifiedDateTime");
    var rawItems = listOfMap(children.get("value"));
    return Map.of(
      "drive", publicDrive(drive),
      "current", Map.of(
        "id", targetItemId,
        "name", "root".equals(targetItemId) ? "OneDrive" : ""
      ),
      "items", rawItems.stream().map(this::normalizeItem).toList()
    );
  }

  Map<String, Object> mkdir(String token, String driveId, Map<String, Object> body) {
    var user = auth.requireUser(token);
    var drive = requireFreshDrive(user.id(), driveId);
    var parentId = string(body.get("parentId"));
    var name = string(body.get("name"));
    if (name.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "文件夹名称不能为空");
    var itemPath = parentId.isBlank() || "root".equals(parentId)
      ? "/drives/" + enc(drive.driveId()) + "/root/children"
      : "/drives/" + enc(drive.driveId()) + "/items/" + enc(parentId) + "/children";
    var result = graphJson(drive.accessToken(), "POST", itemPath, Map.of(
      "name", name,
      "folder", Map.of(),
      "@microsoft.graph.conflictBehavior", "rename"
    ));
    return normalizeItem(result);
  }

  void delete(String token, String driveId, String itemId) {
    var user = auth.requireUser(token);
    var drive = requireFreshDrive(user.id(), driveId);
    graphNoBody(drive.accessToken(), "DELETE", "/drives/" + enc(drive.driveId()) + "/items/" + enc(itemId));
  }

  Map<String, Object> rename(String token, String driveId, String itemId, Map<String, Object> body) {
    var user = auth.requireUser(token);
    var drive = requireFreshDrive(user.id(), driveId);
    var name = string(body.get("name"));
    if (name.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "名称不能为空");
    var result = graphJson(drive.accessToken(), "PATCH", "/drives/" + enc(drive.driveId()) + "/items/" + enc(itemId), Map.of("name", name));
    return normalizeItem(result);
  }

  Map<String, Object> download(String token, String driveId, String itemId) {
    var user = auth.requireUser(token);
    var drive = requireFreshDrive(user.id(), driveId);
    var item = graphGet(drive.accessToken(), "/drives/" + enc(drive.driveId()) + "/items/" + enc(itemId) + "?select=id,name,size,file,@microsoft.graph.downloadUrl");
    var originalUrl = string(item.get("@microsoft.graph.downloadUrl"));
    var urls = applyDownloadCdnHosts(originalUrl, effectiveCdnHosts(userConfig(user.id())));
    var url = urls.isEmpty() ? originalUrl : urls.get(0);
    if (url.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "OneDrive 没有返回下载地址");
    return Map.of(
      "url", url,
      "urls", urls.isEmpty() ? List.of(url) : urls,
      "name", string(item.get("name")),
      "size", number(item.get("size")),
      "acceptRanges", true
    );
  }

  Map<String, Object> uploadSession(String token, String driveId, Map<String, Object> body) {
    var user = auth.requireUser(token);
    var drive = requireFreshDrive(user.id(), driveId);
    var parentId = string(body.get("parentId"));
    var name = string(body.get("name"));
    if (name.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "文件名不能为空");
    var path = parentId.isBlank() || "root".equals(parentId)
      ? "/drives/" + enc(drive.driveId()) + "/root:/" + encPathSegment(name) + ":/createUploadSession"
      : "/drives/" + enc(drive.driveId()) + "/items/" + enc(parentId) + ":/" + encPathSegment(name) + ":/createUploadSession";
    var response = graphJson(drive.accessToken(), "POST", path, Map.of(
      "item", Map.of("@microsoft.graph.conflictBehavior", string(body.getOrDefault("conflictBehavior", "replace")))
    ));
    return Map.of(
      "uploadUrl", string(response.get("uploadUrl")),
      "expirationDateTime", string(response.get("expirationDateTime")),
      "chunkSize", 10 * 1024 * 1024,
      "threadPolicy", "onedrive-sequential"
    );
  }

  private String buildAuthorizeUrl(Map<String, Object> config) {
    var configuredClientId = effectiveClientId(config);
    var configuredRedirectUri = effectiveRedirectUri(config);
    var params = new LinkedHashMap<String, String>();
    params.put("client_id", configuredClientId);
    params.put("response_type", "code");
    params.put("redirect_uri", configuredRedirectUri);
    params.put("response_mode", "query");
    params.put("scope", SCOPES);
    params.put("prompt", "select_account");
    return authorizeUrl() + "?" + form(params);
  }

  private synchronized CloudDriveRecord requireFreshDrive(String userId, String id) {
    var drive = requireDrive(userId, id);
    if (drive.tokenExpiresAt() > now() + 60_000L && !drive.accessToken().isBlank()) return drive;
    var config = userConfig(userId);
    if (!isConfigured(config) && !isPlatformConfigured()) throw new ApiException(HttpStatus.BAD_REQUEST, "OneDrive 应用未配置");
    var configuredClientId = effectiveClientId(config);
    var configuredClientSecret = effectiveClientSecret(config);
    var configuredRedirectUri = effectiveRedirectUri(config);
    var tokenPayload = tokenRequest(Map.of(
      "client_id", configuredClientId,
      "client_secret", configuredClientSecret,
      "redirect_uri", configuredRedirectUri,
      "grant_type", "refresh_token",
      "refresh_token", drive.refreshToken(),
      "scope", SCOPES
    ));
    var accessToken = string(tokenPayload.get("access_token"));
    var refreshToken = firstNonBlank(string(tokenPayload.get("refresh_token")), drive.refreshToken());
    if (accessToken.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "刷新 OneDrive 授权失败");
    var expiresAt = now() + Math.max(60, number(tokenPayload.get("expires_in")) - 120) * 1000L;
    jdbc.update(
      "update beiming_cloud_drives set access_token = ?, refresh_token = ?, token_expires_at = ?, updated_at = ? where id = ? and user_id = ?",
      accessToken, refreshToken, expiresAt, now(), drive.id(), userId
    );
    return requireDrive(userId, id);
  }

  private CloudDriveRecord requireDrive(String userId, String id) {
    var rows = jdbc.query("select * from beiming_cloud_drives where id = ? and user_id = ?", driveMapper, id, userId);
    if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "云盘未挂载");
    return rows.get(0);
  }

  private Map<String, Object> publicDrive(CloudDriveRecord drive) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", drive.id());
    item.put("provider", drive.provider());
    item.put("displayName", drive.displayName());
    item.put("accountName", drive.accountName());
    item.put("driveId", drive.driveId());
    item.put("rootItemId", drive.rootItemId());
    item.put("authMode", drive.authMode());
    item.put("createdAt", drive.createdAt());
    item.put("updatedAt", drive.updatedAt());
    return item;
  }

  private CloudDriveRecord upsertDrive(String userId, String accessToken, String refreshToken, long expiresAt, String authMode, String preferredName) {
    var profile = graphGet(accessToken, "/me");
    var drive = graphGet(accessToken, "/me/drive");
    var driveId = string(drive.get("id"));
    var rootId = string(map(drive.get("root")).get("id"));
    if (driveId.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "无法读取 OneDrive 磁盘信息");
    var accountName = firstNonBlank(string(profile.get("userPrincipalName")), string(profile.get("mail")), string(profile.get("displayName")), "OneDrive");
    var displayName = firstNonBlank(preferredName, accountName, "OneDrive");
    var existing = jdbc.query("select * from beiming_cloud_drives where user_id = ? and provider = ? and drive_id = ?", driveMapper, userId, "onedrive", driveId);
    var id = existing.isEmpty() ? "drive-" + UUID.randomUUID().toString().substring(0, 8) : existing.get(0).id();
    var timestamp = now();
    if (existing.isEmpty()) {
      jdbc.update(
        """
          insert into beiming_cloud_drives
          (id, user_id, provider, display_name, account_name, drive_id, root_item_id, access_token, refresh_token, token_expires_at, auth_mode, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id, userId, "onedrive", displayName, accountName, driveId, rootId, accessToken, refreshToken, expiresAt, authMode, timestamp, timestamp
      );
    } else {
      jdbc.update(
        """
          update beiming_cloud_drives
          set display_name = ?, account_name = ?, root_item_id = ?, access_token = ?, refresh_token = ?, token_expires_at = ?, auth_mode = ?, updated_at = ?
          where id = ? and user_id = ?
        """,
        displayName, accountName, rootId, accessToken, refreshToken, expiresAt, authMode, timestamp, id, userId
      );
    }
    return requireDrive(userId, id);
  }

  private Map<String, Object> normalizeItem(Map<String, Object> raw) {
    Map<String, Object> item = new LinkedHashMap<>();
    var folder = map(raw.get("folder"));
    var file = map(raw.get("file"));
    item.put("id", string(raw.get("id")));
    item.put("name", string(raw.get("name")));
    item.put("type", folder.isEmpty() ? "f" : "d");
    item.put("size", number(raw.get("size")));
    item.put("modifiedAt", string(raw.get("lastModifiedDateTime")));
    item.put("mimeType", string(file.get("mimeType")));
    item.put("childCount", number(folder.get("childCount")));
    return item;
  }

  private Map<String, Object> tokenRequest(Map<String, String> fields) {
    try {
      var request = HttpRequest.newBuilder(URI.create(tokenUrl()))
        .POST(HttpRequest.BodyPublishers.ofString(form(fields)))
        .header("content-type", "application/x-www-form-urlencoded")
        .timeout(Duration.ofSeconds(25))
        .build();
      var response = sendWithRetry(request);
      return parseGraphResponse(response, "OneDrive token request failed");
    } catch (ApiException error) {
      throw error;
    } catch (Exception error) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "OneDrive 授权请求失败: " + error.getMessage());
    }
  }

  private Map<String, Object> graphGet(String accessToken, String path) {
    return graphRequest(accessToken, "GET", path, null);
  }

  private Map<String, Object> graphJson(String accessToken, String method, String path, Map<String, Object> body) {
    return graphRequest(accessToken, method, path, body);
  }

  private void graphNoBody(String accessToken, String method, String path) {
    graphRequest(accessToken, method, path, null);
  }

  private Map<String, Object> graphRequest(String accessToken, String method, String path, Map<String, Object> body) {
    try {
      var builder = HttpRequest.newBuilder(URI.create(GRAPH_ROOT + path))
        .header("Authorization", "Bearer " + accessToken)
        .timeout(Duration.ofSeconds(30));
      if (body == null) {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        builder.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
          .header("content-type", "application/json");
      }
      return parseGraphResponse(sendWithRetry(builder.build()), "OneDrive request failed");
    } catch (ApiException error) {
      throw error;
    } catch (Exception error) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "OneDrive 请求失败: " + error.getMessage());
    }
  }

  private Map<String, Object> parseGraphResponse(HttpResponse<String> response, String fallback) throws Exception {
    var body = response.body() == null ? "" : response.body();
    if (response.statusCode() == 204) return Map.of();
    var payload = body.isBlank() ? Map.<String, Object>of() : mapper.readValue(body, MAP_TYPE);
    if (response.statusCode() >= 200 && response.statusCode() < 300) return payload;
    var error = map(payload.get("error"));
    var message = firstNonBlank(string(error.get("message")), string(payload.get("error_description")), fallback);
    throw new ApiException(HttpStatus.BAD_GATEWAY, message);
  }

  private HttpResponse<String> sendWithRetry(HttpRequest request) throws Exception {
    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (Exception first) {
      Thread.sleep(350);
      try {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (Exception second) {
        second.addSuppressed(first);
        throw second;
      }
    }
  }

  private Map<String, Object> userConfig(String userId) {
    var rows = jdbc.queryForList("select client_id, client_secret, redirect_uri, cdn_host from beiming_cloud_oauth_configs where user_id = ? and provider = ?", userId, "onedrive");
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }

  private boolean isConfigured(Map<String, Object> config) {
    return !effectiveClientId(config).isBlank()
      && !effectiveClientSecret(config).isBlank()
      && !effectiveRedirectUri(config).isBlank();
  }

  private boolean isPlatformConfigured() {
    return !clientId.isBlank() && !clientSecret.isBlank() && !redirectUri.isBlank();
  }

  private String effectiveClientId(Map<String, Object> config) {
    return firstNonBlank(clientId, string(config.get("client_id")));
  }

  private String effectiveClientSecret(Map<String, Object> config) {
    return firstNonBlank(clientSecret, string(config.get("client_secret")));
  }

  private String effectiveRedirectUri(Map<String, Object> config) {
    return firstNonBlank(redirectUri, string(config.get("redirect_uri")));
  }

  private String effectiveCdnHost(Map<String, Object> config) {
    return normalizeCdnHosts(string(config.get("cdn_host")));
  }

  private List<String> effectiveCdnHosts(Map<String, Object> config) {
    var text = effectiveCdnHost(config);
    if (text.isBlank()) return List.of();
    return Arrays.stream(text.split("\\n"))
      .map(this::normalizeSingleCdnHost)
      .filter((host) -> !host.isBlank())
      .distinct()
      .toList();
  }

  private List<String> applyDownloadCdnHosts(String url, List<String> cdnHosts) {
    if (url.isBlank()) return List.of();
    if (cdnHosts == null || cdnHosts.isEmpty()) return List.of(url);
    return cdnHosts.stream()
      .map((host) -> applyDownloadCdnHost(url, host))
      .filter((item) -> !item.isBlank())
      .distinct()
      .toList();
  }

  private String applyDownloadCdnHost(String url, String cdnHost) {
    var host = normalizeSingleCdnHost(cdnHost);
    if (url.isBlank() || host.isBlank()) return url;
    var schemeIndex = url.indexOf("://");
    if (schemeIndex <= 0) return url;
    var authorityStart = schemeIndex + 3;
    var authorityEnd = url.length();
    for (var i = authorityStart; i < url.length(); i++) {
      var ch = url.charAt(i);
      if (ch == '/' || ch == '?' || ch == '#') {
        authorityEnd = i;
        break;
      }
    }
    return url.substring(0, authorityStart) + host + url.substring(authorityEnd);
  }

  private String normalizeCdnHosts(String value) {
    var text = string(value).trim();
    if (text.isBlank()) return "";
    return Arrays.stream(text.split("[,\\s]+"))
      .map(this::normalizeSingleCdnHost)
      .filter((host) -> !host.isBlank())
      .distinct()
      .reduce((left, right) -> left + "\n" + right)
      .orElse("");
  }

  private String normalizeSingleCdnHost(String value) {
    var text = string(value).trim();
    if (text.isBlank()) return "";
    if (text.contains("://")) {
      try {
        var uri = URI.create(text);
        text = firstNonBlank(uri.getRawAuthority(), uri.getHost());
      } catch (IllegalArgumentException ignored) {
        text = "";
      }
    }
    text = text.replaceAll("^/+|/+$", "").trim();
    var slash = text.indexOf('/');
    if (slash >= 0) text = text.substring(0, slash);
    if (text.contains("?") || text.contains("#") || text.contains("@") || text.contains("\\\\")) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "CDN 域名格式不正确");
    }
    return text;
  }

  private String frontendCloudUrl(String query) {
    var suffix = query == null || query.isBlank() ? "view=cloud" : "view=cloud&" + query;
    return frontendOrigin + "/?" + suffix;
  }

  private void requireConfigured(Map<String, Object> config) {
    if (!isConfigured(config)) throw new ApiException(HttpStatus.BAD_REQUEST, "请先配置 OneDrive Client ID、Client Secret 和 Redirect URI");
  }

  private String mask(String value) {
    var text = string(value);
    if (text.length() <= 8) return text;
    return text.substring(0, 4) + "..." + text.substring(text.length() - 4);
  }

  private String form(Map<String, String> values) {
    return values.entrySet().stream()
      .map((entry) -> enc(entry.getKey()) + "=" + enc(entry.getValue()))
      .reduce((left, right) -> left + "&" + right)
      .orElse("");
  }

  private String authorizeUrl() {
    return MICROSOFT_LOGIN_ROOT + "/" + tenant + "/oauth2/v2.0/authorize";
  }

  private String tokenUrl() {
    return MICROSOFT_LOGIN_ROOT + "/" + tenant + "/oauth2/v2.0/token";
  }

  private String enc(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private String encPathSegment(String value) {
    return enc(value).replace("+", "%20").replace("%2F", "/");
  }

  private Map<String, Object> map(Object value) {
    if (value instanceof Map<?, ?> raw) {
      Map<String, Object> result = new LinkedHashMap<>();
      raw.forEach((key, item) -> result.put(String.valueOf(key), item));
      return result;
    }
    if (value instanceof JsonNode node && node.isObject()) {
      return mapper.convertValue(node, MAP_TYPE);
    }
    return Map.of();
  }

  private List<Map<String, Object>> listOfMap(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream()
      .filter(Objects::nonNull)
      .map(this::map)
      .filter((item) -> !item.isEmpty())
      .toList();
  }

  private String firstNonBlank(String... values) {
    for (var value : values) {
      if (value != null && !value.isBlank()) return value;
    }
    return "";
  }

  private String trimTrailingSlash(String value) {
    return string(value).replaceAll("/+$", "");
  }

  private String normalizeTenant(String value) {
    var text = string(value);
    return text.isBlank() ? "common" : text.replaceAll("^/+|/+$", "");
  }

  private String string(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private long number(Object value) {
    if (value instanceof Number number) return number.longValue();
    try {
      return Long.parseLong(string(value));
    } catch (Exception ignored) {
      return 0;
    }
  }

  private long now() {
    return Instant.now().toEpochMilli();
  }
}
