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
import java.util.Base64;
import java.util.*;

@Service
public class CloudDriveService {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final String GRAPH_ROOT = "https://graph.microsoft.com/v1.0";
  private static final String MICROSOFT_LOGIN_ROOT = "https://login.microsoftonline.com";
  private static final String SCOPES = "offline_access Files.ReadWrite.All User.Read";
  private static final String AUTH_MODE_BEIMING = "beiming";
  private static final String DRIVE_ITEM_SELECT = "id,name,folder,file,size,lastModifiedDateTime,parentReference,remoteItem,package";
  private static final long OAUTH_STATE_TTL_MS = 10 * 60 * 1000L;

  private final AuthService auth;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final HttpClient httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(12))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .version(HttpClient.Version.HTTP_2)
    .build();
  private final HttpClient noRedirectHttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(12))
    .followRedirects(HttpClient.Redirect.NEVER)
    .version(HttpClient.Version.HTTP_2)
    .build();
  private final String clientId;
  private final String clientSecret;
  private final String redirectUri;
  private final String tenant;
  private final String frontendOrigin;

  private record GraphItemRef(String driveId, String itemId, String name) {}

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
      .map((drive) -> publicDriveWithQuota(user.id(), drive))
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
    var nextClientId = firstNonBlank(string(body.get("clientId")), string(previous.get("client_id")));
    var nextClientSecret = firstNonBlank(string(body.get("clientSecret")), string(previous.get("client_secret")));
    var nextRedirectUri = firstNonBlank(string(body.get("redirectUri")), string(previous.get("redirect_uri")));
    var nextCdnHost = normalizeCdnHosts(string(body.get("cdnHost")));
    var hasOAuthFields = body.containsKey("clientId") || body.containsKey("clientSecret") || body.containsKey("redirectUri");
    if (hasOAuthFields) {
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

  synchronized Map<String, Object> mountSharedFolder(String token, Map<String, Object> body) {
    var user = auth.requireUser(token);
    var link = string(body.get("url"));
    if (link.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "共享链接不能为空");
    var baseDriveId = firstNonBlank(string(body.get("driveId")), latestPersonalDriveId(user.id()));
    if (baseDriveId.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "请先挂载一个 OneDrive 账号");
    var baseDrive = requireFreshDrive(user.id(), baseDriveId);
    var shared = graphGet(baseDrive.accessToken(), "/shares/" + shareId(link) + "/driveItem?$select=" + DRIVE_ITEM_SELECT);
    var remote = map(shared.get("remoteItem"));
    var parent = map(firstNonNull(remote.get("parentReference"), shared.get("parentReference")));
    var sourceDriveId = firstNonBlank(string(parent.get("driveId")), string(map(shared.get("parentReference")).get("driveId")));
    var sourceItemId = firstNonBlank(string(remote.get("id")), string(shared.get("id")));
    var name = firstNonBlank(string(body.get("name")), string(remote.get("name")), string(shared.get("name")), "共享文件夹");
    if (sourceDriveId.isBlank() || sourceItemId.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "无法解析共享文件夹信息");
    if (map(shared.get("folder")).isEmpty() && map(remote.get("folder")).isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "目前只支持挂载共享文件夹");
    }
    var timestamp = now();
    var existing = jdbc.query("select * from beiming_cloud_drives where user_id = ? and provider = ? and drive_id = ? and root_item_id = ?", driveMapper, user.id(), "onedrive-shared", sourceDriveId, sourceItemId);
    var id = existing.isEmpty() ? "share-" + UUID.randomUUID().toString().substring(0, 8) : existing.get(0).id();
    if (existing.isEmpty()) {
      jdbc.update(
        """
          insert into beiming_cloud_drives
          (id, user_id, provider, display_name, account_name, drive_id, root_item_id, access_token, refresh_token, token_expires_at, auth_mode, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id, user.id(), "onedrive-shared", name, baseDrive.accountName(), sourceDriveId, sourceItemId,
        baseDrive.accessToken(), baseDrive.refreshToken(), baseDrive.tokenExpiresAt(), "shared-folder", timestamp, timestamp
      );
    } else {
      jdbc.update(
        """
          update beiming_cloud_drives
          set display_name = ?, account_name = ?, access_token = ?, refresh_token = ?, token_expires_at = ?, auth_mode = ?, updated_at = ?
          where id = ? and user_id = ?
        """,
        name, baseDrive.accountName(), baseDrive.accessToken(), baseDrive.refreshToken(), baseDrive.tokenExpiresAt(), "shared-folder", timestamp, id, user.id()
      );
    }
    return publicDrive(requireDrive(user.id(), id));
  }

  synchronized Map<String, Object> mountSharedItem(String token, Map<String, Object> body) {
    var user = auth.requireUser(token);
    var sourceDriveId = string(body.get("sourceDriveId"));
    var sourceItemId = string(body.get("sourceItemId"));
    var name = string(body.get("name"));
    if (sourceDriveId.isBlank() || sourceItemId.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "共享项目参数不完整");
    var baseDriveId = firstNonBlank(string(body.get("targetDriveId")), latestPersonalDriveId(user.id()));
    if (baseDriveId.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "请先挂载一个 OneDrive 账号");
    var baseDrive = requireFreshDrive(user.id(), baseDriveId);
    var source = requireFreshDrive(user.id(), sourceDriveId);
    if (!source.driveId().equals(sourceDriveId)) throw new ApiException(HttpStatus.BAD_REQUEST, "只能挂载已授权账号里的共享文件夹");
    var item = graphGet(source.accessToken(), "/drives/" + enc(source.driveId()) + "/items/" + enc(sourceItemId) + "?$select=id,name,folder");
    if (map(item.get("folder")).isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "目前只支持把文件夹挂载到云盘列表");
    var displayName = firstNonBlank(name, string(item.get("name")), "共享文件夹");
    var timestamp = now();
    var existing = jdbc.query("select * from beiming_cloud_drives where user_id = ? and provider = ? and drive_id = ? and root_item_id = ?", driveMapper, user.id(), "onedrive-shared", source.driveId(), sourceItemId);
    var id = existing.isEmpty() ? "share-" + UUID.randomUUID().toString().substring(0, 8) : existing.get(0).id();
    if (existing.isEmpty()) {
      jdbc.update(
        """
          insert into beiming_cloud_drives
          (id, user_id, provider, display_name, account_name, drive_id, root_item_id, access_token, refresh_token, token_expires_at, auth_mode, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id, user.id(), "onedrive-shared", displayName, baseDrive.accountName(), source.driveId(), sourceItemId,
        baseDrive.accessToken(), baseDrive.refreshToken(), baseDrive.tokenExpiresAt(), "shared-folder", timestamp, timestamp
      );
    } else {
      jdbc.update(
        """
          update beiming_cloud_drives
          set display_name = ?, account_name = ?, access_token = ?, refresh_token = ?, token_expires_at = ?, auth_mode = ?, updated_at = ?
          where id = ? and user_id = ?
        """,
        displayName, baseDrive.accountName(), baseDrive.accessToken(), baseDrive.refreshToken(), baseDrive.tokenExpiresAt(), "shared-folder", timestamp, id, user.id()
      );
    }
    return publicDrive(requireDrive(user.id(), id));
  }

  synchronized void disconnect(String token, String driveId) {
    var user = auth.requireUser(token);
    jdbc.update("delete from beiming_cloud_drives where id = ? and user_id = ?", driveId, user.id());
  }

  Map<String, Object> list(String token, String driveId, String itemId, String cursor, int limit) {
    var user = auth.requireUser(token);
    var drive = requireFreshDrive(user.id(), driveId);
    var requestedItemId = string(itemId).isBlank() ? "root" : string(itemId);
    var target = graphItemRef(drive, requestedItemId);
    var safeLimit = Math.max(20, Math.min(200, limit));
    var requestPath = string(cursor);
    if (requestPath.isBlank()) {
      var itemPath = graphItemPath(target);
      requestPath = itemPath + "/children?$top=" + safeLimit + "&$select=" + DRIVE_ITEM_SELECT;
    }
    var children = graphGet(drive.accessToken(), requestPath);
    var rawItems = listOfMap(children.get("value"));
    var nextCursor = graphCursor(string(children.get("@odata.nextLink")));
    var items = rawItems.stream().map((raw) -> normalizeItem(raw, drive, target.driveId())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    return Map.of(
      "drive", publicDrive(drive),
      "current", Map.of(
        "id", "root".equals(requestedItemId) ? "root" : requestedItemId,
        "name", "root".equals(requestedItemId) ? drive.displayName() : target.name()
      ),
      "items", items,
      "nextCursor", nextCursor,
      "hasMore", !nextCursor.isBlank()
    );
  }

  Map<String, Object> mkdir(String token, String driveId, Map<String, Object> body) {
    var user = auth.requireUser(token);
    var drive = requireFreshDrive(user.id(), driveId);
    var parentId = string(body.get("parentId"));
    var name = string(body.get("name"));
    if (name.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "文件夹名称不能为空");
    var targetParent = graphItemRef(drive, parentId);
    var itemPath = "root".equals(targetParent.itemId())
      ? "/drives/" + enc(drive.driveId()) + "/root/children"
      : "/drives/" + enc(targetParent.driveId()) + "/items/" + enc(targetParent.itemId()) + "/children";
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
    if (deleteShortcutItem(user.id(), drive, itemId)) return;
    var target = graphItemRef(drive, itemId);
    graphNoBody(drive.accessToken(), "DELETE", "/drives/" + enc(target.driveId()) + "/items/" + enc(target.itemId()));
  }

  Map<String, Object> rename(String token, String driveId, String itemId, Map<String, Object> body) {
    var user = auth.requireUser(token);
    var drive = requireFreshDrive(user.id(), driveId);
    var name = string(body.get("name"));
    if (name.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "名称不能为空");
    var renamedShortcut = renameShortcutItem(user.id(), drive, itemId, name);
    if (!renamedShortcut.isEmpty()) return renamedShortcut;
    var target = graphItemRef(drive, itemId);
    var result = graphJson(drive.accessToken(), "PATCH", "/drives/" + enc(target.driveId()) + "/items/" + enc(target.itemId()), Map.of("name", name));
    return normalizeItem(result);
  }

  Map<String, Object> copy(String token, String driveId, String itemId, Map<String, Object> body) {
    var user = auth.requireUser(token);
    var sourceDrive = requireFreshDrive(user.id(), driveId);
    var targetDriveId = string(body.get("targetDriveId"));
    var targetDrive = targetDriveId.isBlank() || targetDriveId.equals(driveId)
      ? sourceDrive
      : requireFreshDrive(user.id(), targetDriveId);
    var parentId = string(body.get("parentId"));
    if (parentId.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "目标目录不能为空");
    if (!sourceDrive.driveId().equals(targetDrive.driveId())) {
      return createRemoteShortcutAcrossDrives(sourceDrive, targetDrive, itemId, parentId, string(body.get("name")));
    }
    var sourceItem = graphItemRef(sourceDrive, itemId);
    var item = graphGet(sourceDrive.accessToken(), graphItemPath(sourceItem) + "?$select=id,name");
    var originalName = string(item.get("name"));
    var payload = new LinkedHashMap<String, Object>();
    payload.put("parentReference", graphParentReference(sourceDrive, parentId));
    payload.put("name", firstNonBlank(string(body.get("name")), originalName));
    var operationUrl = graphAccepted(sourceDrive.accessToken(), "POST", graphItemPath(sourceItem) + "/copy", payload);
    try {
      var copied = waitGraphCopy(sourceDrive.accessToken(), sourceDrive, operationUrl);
      if (!copied.isEmpty()) return normalizeItem(copied);
    } catch (ApiException ignored) {
      // OneDrive has already accepted the server-side copy. Some monitor URLs reject
      // delegated tokens after completion, so let the caller refresh the target folder.
    }
    return Map.of(
      "accepted", true,
      "operationUrl", operationUrl,
      "name", string(payload.get("name"))
    );
  }

  Map<String, Object> move(String token, String driveId, String itemId, Map<String, Object> body) {
    var user = auth.requireUser(token);
    var sourceDrive = requireFreshDrive(user.id(), driveId);
    var targetDriveId = string(body.get("targetDriveId"));
    var targetDrive = targetDriveId.isBlank() || targetDriveId.equals(driveId)
      ? sourceDrive
      : requireFreshDrive(user.id(), targetDriveId);
    var parentId = string(body.get("parentId"));
    if (parentId.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "目标目录不能为空");
    if (!sourceDrive.driveId().equals(targetDrive.driveId())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "跨账号剪切不能用快捷入口实现；请使用复制添加快捷入口，或在同一个 OneDrive 账号内移动");
    }
    var payload = new LinkedHashMap<String, Object>();
    payload.put("parentReference", graphParentReference(sourceDrive, parentId));
    var name = string(body.get("name"));
    if (!name.isBlank()) payload.put("name", name);
    var sourceItem = graphItemRef(sourceDrive, itemId);
    var result = graphJson(sourceDrive.accessToken(), "PATCH", graphItemPath(sourceItem), payload);
    return normalizeItem(result);
  }

  Map<String, Object> download(String token, String driveId, String itemId) {
    var user = auth.requireUser(token);
    var drive = requireFreshDrive(user.id(), driveId);
    var targetItem = graphItemRef(drive, itemId);
    var itemPath = graphItemPath(targetItem);
    var item = graphGet(drive.accessToken(), itemPath + "?$select=" + DRIVE_ITEM_SELECT);
    item = enrichRemoteItemForDownload(drive.accessToken(), item, itemPath);
    var originalUrl = string(firstNonNull(item.get("@microsoft.graph.downloadUrl"), map(item.get("remoteItem")).get("@microsoft.graph.downloadUrl")));
    if (originalUrl.isBlank()) {
      item = graphGet(drive.accessToken(), itemPath);
      item = enrichRemoteItemForDownload(drive.accessToken(), item, itemPath);
      originalUrl = string(firstNonNull(item.get("@microsoft.graph.downloadUrl"), map(item.get("remoteItem")).get("@microsoft.graph.downloadUrl")));
    }
    if (originalUrl.isBlank() && item.get("file") != null) {
      originalUrl = graphContentRedirectUrl(drive.accessToken(), itemPath + "/content");
    }
    var urls = applyDownloadCdnHosts(originalUrl, effectiveCdnHosts(userConfig(user.id())));
    var url = urls.isEmpty() ? originalUrl : urls.get(0);
    if (url.isBlank()) {
      var message = item.get("file") == null ? "文件夹不能直接下载" : "OneDrive 没有返回下载地址";
      throw new ApiException(HttpStatus.BAD_GATEWAY, message);
    }
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
    var targetParent = graphItemRef(drive, parentId);
    var path = "root".equals(targetParent.itemId())
      ? "/drives/" + enc(drive.driveId()) + "/root:/" + encPathSegment(name) + ":/createUploadSession"
      : "/drives/" + enc(targetParent.driveId()) + "/items/" + enc(targetParent.itemId()) + ":/" + encPathSegment(name) + ":/createUploadSession";
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

  private Map<String, Object> createRemoteShortcutAcrossDrives(CloudDriveRecord sourceDrive, CloudDriveRecord targetDrive, String itemId, String targetParentId, String preferredName) {
    if (!string(targetParentId).isBlank() && !"root".equals(string(targetParentId))) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "OneDrive 只允许把共享文件夹快捷入口添加到目标账号根目录");
    }
    var source = graphGet(sourceDrive.accessToken(), "/drives/" + enc(sourceDrive.driveId()) + "/items/" + enc(itemId) + "?$select=" + DRIVE_ITEM_SELECT);
    var name = firstNonBlank(preferredName, string(source.get("name")));
    if (name.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "文件名称不能为空");
    if (map(source.get("folder")).isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "OneDrive 跨账号远程复制只支持共享文件夹快捷入口，文件不能不经下载直接转存");
    }
    var sourceItem = graphItemRef(sourceDrive, itemId);
    grantShortcutAccess(sourceDrive, targetDrive, sourceItem.itemId());
    Map<String, Object> sharedItem = Map.of();
    try {
      sharedItem = waitForTargetSharedFolder(sourceDrive, targetDrive, sourceItem.itemId());
    } catch (ApiException error) {
      // The native shortcut API can still succeed shortly after invite even when
      // sharedWithMe has not indexed the folder yet, so continue with source IDs.
    }
    try {
      return sharedItem.isEmpty()
        ? createRemoteShortcut(targetDrive, sourceItem.driveId(), sourceItem.itemId(), name)
        : createRemoteShortcut(targetDrive, sharedItem, name);
    } catch (ApiException error) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "OneDrive 原生快捷方式创建失败: " + error.getMessage());
    }
  }

  private Map<String, Object> createRemoteShortcut(CloudDriveRecord targetDrive, Map<String, Object> sharedItem, String name) {
    var remote = map(sharedItem.get("remoteItem"));
    var parent = map(remote.get("parentReference"));
    var remoteItemId = string(remote.get("id"));
    var remoteDriveId = string(parent.get("driveId"));
    if (remoteItemId.isBlank() || remoteDriveId.isBlank()) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "目标账号已收到共享，但缺少 OneDrive 快捷方式参数");
    }
    return createRemoteShortcut(targetDrive, remoteDriveId, remoteItemId, name);
  }

  private Map<String, Object> createRemoteShortcut(CloudDriveRecord targetDrive, String remoteDriveId, String remoteItemId, String name) {
    remoteDriveId = string(remoteDriveId);
    remoteItemId = string(remoteItemId);
    if (remoteDriveId.isBlank() || remoteItemId.isBlank()) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "缺少 OneDrive 原生快捷方式参数");
    }
    var payload = new LinkedHashMap<String, Object>();
    payload.put("name", name);
    payload.put("remoteItem", Map.of(
      "id", remoteItemId,
      "parentReference", Map.of("driveId", remoteDriveId)
    ));
    var errors = new ArrayList<String>();
    var paths = new ArrayList<String>();
    paths.add("/me/drive/root/children");
    paths.add("/drives/" + enc(targetDrive.driveId()) + "/root/children");
    if (!targetDrive.rootItemId().isBlank()) {
      paths.add("/drives/" + enc(targetDrive.driveId()) + "/items/" + enc(targetDrive.rootItemId()) + "/children");
    }
    for (var path : paths) {
      try {
        return normalizeItem(graphJson(targetDrive.accessToken(), "POST", path, payload));
      } catch (ApiException error) {
        errors.add(path + " -> " + error.getMessage());
      }
    }
    throw new ApiException(HttpStatus.BAD_GATEWAY, String.join("; ", errors));
  }

  private Map<String, Object> saveVirtualShortcut(CloudDriveRecord targetDrive, Map<String, Object> sharedItem, String name, String graphError) {
    var remote = map(sharedItem.get("remoteItem"));
    var parent = map(remote.get("parentReference"));
    var sourceDriveId = string(parent.get("driveId"));
    var sourceItemId = string(remote.get("id"));
    if (sourceDriveId.isBlank() || sourceItemId.isBlank()) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "目标账号已收到共享，但缺少快捷方式参数");
    }
    return saveVirtualShortcut(targetDrive, sourceDriveId, sourceItemId, name, graphError);
  }

  private Map<String, Object> saveVirtualShortcut(CloudDriveRecord targetDrive, String sourceDriveId, String sourceItemId, String name, String graphError) {
    sourceDriveId = string(sourceDriveId);
    sourceItemId = string(sourceItemId);
    if (sourceDriveId.isBlank() || sourceItemId.isBlank()) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "缺少快捷方式参数");
    }
    var timestamp = now();
    var existing = jdbc.queryForList(
      "select * from beiming_cloud_shortcuts where user_id = ? and target_drive_id = ? and source_drive_id = ? and source_item_id = ?",
      targetDrive.userId(), targetDrive.id(), sourceDriveId, sourceItemId
    );
    var id = existing.isEmpty() ? "shortcut-" + UUID.randomUUID().toString().substring(0, 8) : string(existing.get(0).get("id"));
    if (existing.isEmpty()) {
      jdbc.update(
        """
          insert into beiming_cloud_shortcuts
          (id, user_id, target_drive_id, source_drive_id, source_item_id, name, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id, targetDrive.userId(), targetDrive.id(), sourceDriveId, sourceItemId, name, timestamp, timestamp
      );
    } else {
      jdbc.update(
        """
          update beiming_cloud_shortcuts
          set name = ?, updated_at = ?
          where id = ? and user_id = ? and target_drive_id = ?
        """,
        name, timestamp, id, targetDrive.userId(), targetDrive.id()
      );
    }
    var item = shortcutItem(Map.of(
      "id", id,
      "name", name,
      "source_drive_id", sourceDriveId,
      "source_item_id", sourceItemId,
      "updated_at", timestamp
    ));
    item.put("message", "已在北冥目标目录创建快捷方式");
    item.put("graphError", graphError);
    return item;
  }

  private List<Map<String, Object>> shortcutItems(String userId, CloudDriveRecord targetDrive) {
    return jdbc.queryForList(
        "select * from beiming_cloud_shortcuts where user_id = ? and target_drive_id = ? order by created_at asc",
        userId, targetDrive.id()
      )
      .stream()
      .map(this::shortcutItem)
      .toList();
  }

  private Map<String, Object> shortcutItem(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", "shortcut:" + string(row.get("id")));
    item.put("name", string(row.get("name")));
    item.put("type", "d");
    item.put("size", 0L);
    var updatedAt = number(row.get("updated_at"));
    item.put("modifiedAt", updatedAt > 0 ? Instant.ofEpochMilli(updatedAt).toString() : "");
    item.put("mimeType", "");
    item.put("childCount", 0L);
    item.put("shortcut", true);
    item.put("virtualShortcut", true);
    return item;
  }

  private boolean deleteShortcutItem(String userId, CloudDriveRecord targetDrive, String itemId) {
    var value = string(itemId);
    if (!value.startsWith("shortcut:")) return false;
    var shortcutId = value.substring("shortcut:".length());
    jdbc.update("delete from beiming_cloud_shortcuts where id = ? and user_id = ? and target_drive_id = ?", shortcutId, userId, targetDrive.id());
    return true;
  }

  private Map<String, Object> renameShortcutItem(String userId, CloudDriveRecord targetDrive, String itemId, String name) {
    var value = string(itemId);
    if (!value.startsWith("shortcut:")) return Map.of();
    var shortcutId = value.substring("shortcut:".length());
    var timestamp = now();
    var updated = jdbc.update(
      "update beiming_cloud_shortcuts set name = ?, updated_at = ? where id = ? and user_id = ? and target_drive_id = ?",
      name, timestamp, shortcutId, userId, targetDrive.id()
    );
    if (updated <= 0) throw new ApiException(HttpStatus.NOT_FOUND, "快捷方式不存在");
    return shortcutItem(Map.of("id", shortcutId, "name", name, "updated_at", timestamp));
  }

  private void grantShortcutAccess(CloudDriveRecord sourceDrive, CloudDriveRecord targetDrive, String itemId) {
    var targetEmail = string(targetDrive.accountName());
    if (!looksLikeEmail(targetEmail)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "目标 OneDrive 账号邮箱不可用，不能自动建立跨账号快捷入口权限");
    }
    try {
      graphJson(sourceDrive.accessToken(), "POST", "/drives/" + enc(sourceDrive.driveId()) + "/items/" + enc(itemId) + "/invite", Map.of(
        "recipients", List.of(Map.of("email", targetEmail)),
        "requireSignIn", true,
        "sendInvitation", false,
        "roles", List.of("read")
      ));
    } catch (ApiException error) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "无法把源文件夹共享给目标账号: " + error.getMessage());
    }
  }

  private Map<String, Object> waitForTargetSharedFolder(CloudDriveRecord sourceDrive, CloudDriveRecord targetDrive, String itemId) {
    var deadline = System.currentTimeMillis() + 12_000L;
    ApiException lastError = null;
    while (System.currentTimeMillis() < deadline) {
      try {
        var sharedItem = findTargetSharedFolder(sourceDrive, targetDrive, itemId);
        if (!sharedItem.isEmpty()) return sharedItem;
      } catch (ApiException error) {
        lastError = error;
      }
      try {
        Thread.sleep(900);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new ApiException(HttpStatus.BAD_GATEWAY, "等待 OneDrive 共享权限同步被中断");
      }
    }
    if (lastError != null) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "目标账号共享权限确认失败: " + lastError.getMessage());
    }
    throw new ApiException(HttpStatus.BAD_GATEWAY, "目标账号暂时还看不到源文件夹，请稍后重试或先在 OneDrive 网页确认共享已生效");
  }

  private Map<String, Object> findTargetSharedFolder(CloudDriveRecord sourceDrive, CloudDriveRecord targetDrive, String itemId) {
    var requestPath = "/me/drive/sharedWithMe?$top=200&$select=id,name,folder,remoteItem";
    while (!requestPath.isBlank()) {
      var page = graphGet(targetDrive.accessToken(), requestPath);
      for (var item : listOfMap(page.get("value"))) {
        var remote = map(item.get("remoteItem"));
        var parent = map(remote.get("parentReference"));
        if (sourceDrive.driveId().equals(string(parent.get("driveId"))) && itemId.equals(string(remote.get("id")))) {
          return item;
        }
      }
      requestPath = graphCursor(string(page.get("@odata.nextLink")));
    }
    return Map.of();
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
    if ("onedrive-shared".equals(drive.provider())) {
      var owner = latestPersonalDrive(userId);
      if (owner.isEmpty()) {
        if (drive.tokenExpiresAt() > now() + 60_000L && !drive.accessToken().isBlank()) return drive;
        throw new ApiException(HttpStatus.BAD_REQUEST, "请先挂载一个 OneDrive 账号");
      }
      var freshOwner = requireFreshDrive(userId, owner.get().id());
      if (!freshOwner.accessToken().equals(drive.accessToken()) || freshOwner.tokenExpiresAt() != drive.tokenExpiresAt()) {
        jdbc.update(
          "update beiming_cloud_drives set access_token = ?, refresh_token = ?, token_expires_at = ?, updated_at = ? where id = ? and user_id = ?",
          freshOwner.accessToken(), freshOwner.refreshToken(), freshOwner.tokenExpiresAt(), now(), drive.id(), userId
        );
        return requireDrive(userId, id);
      }
      return drive;
    }
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

  private Optional<CloudDriveRecord> latestPersonalDrive(String userId) {
    var rows = jdbc.query("select * from beiming_cloud_drives where user_id = ? and provider = ? order by updated_at desc, created_at desc limit 1", driveMapper, userId, "onedrive");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private String latestPersonalDriveId(String userId) {
    return latestPersonalDrive(userId).map(CloudDriveRecord::id).orElse("");
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

  private Map<String, Object> publicDriveWithQuota(String userId, CloudDriveRecord drive) {
    var item = publicDrive(drive);
    if ("onedrive-shared".equals(drive.provider())) return item;
    try {
      var freshDrive = drive.tokenExpiresAt() > now() + 60_000L && !drive.accessToken().isBlank()
        ? drive
        : requireFreshDrive(userId, drive.id());
      var payload = graphGet(freshDrive.accessToken(), "/drives/" + enc(freshDrive.driveId()) + "?$select=quota");
      var quota = map(payload.get("quota"));
      var total = number(quota.get("total"));
      var used = number(quota.get("used"));
      if (total > 0 || used > 0) {
        item.put("quota", Map.of(
          "used", used,
          "total", total,
          "remaining", number(quota.get("remaining")),
          "deleted", number(quota.get("deleted")),
          "state", string(quota.get("state"))
        ));
      }
    } catch (Exception ignored) {
      // Quota display is non-critical; keep the drive usable if Graph is slow.
    }
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
    raw = mergeRemoteItemMetadata(raw);
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
    item.put("shortcut", !map(raw.get("remoteItem")).isEmpty());
    return item;
  }

  private Map<String, Object> normalizeItem(Map<String, Object> raw, CloudDriveRecord appDrive, String graphDriveId) {
    var item = normalizeItem(raw);
    var rawId = string(item.get("id"));
    if (!string(graphDriveId).isBlank() && !graphDriveId.equals(appDrive.driveId()) && !rawId.isBlank()) {
      item.put("id", remoteItemId(graphDriveId, rawId));
    }
    return item;
  }

  private Map<String, Object> mergeRemoteItemMetadata(Map<String, Object> raw) {
    var remote = map(raw.get("remoteItem"));
    if (remote.isEmpty()) return raw;
    var item = new LinkedHashMap<>(raw);
    if (string(item.get("name")).isBlank() && !string(remote.get("name")).isBlank()) item.put("name", remote.get("name"));
    if (number(item.get("size")) <= 0 && number(remote.get("size")) > 0) item.put("size", remote.get("size"));
    if (string(item.get("lastModifiedDateTime")).isBlank() && !string(remote.get("lastModifiedDateTime")).isBlank()) item.put("lastModifiedDateTime", remote.get("lastModifiedDateTime"));
    if (map(item.get("folder")).isEmpty() && !map(remote.get("folder")).isEmpty()) item.put("folder", remote.get("folder"));
    if (map(item.get("file")).isEmpty() && !map(remote.get("file")).isEmpty()) item.put("file", remote.get("file"));
    if (map(item.get("package")).isEmpty() && !map(remote.get("package")).isEmpty()) item.put("package", remote.get("package"));
    if (string(item.get("@microsoft.graph.downloadUrl")).isBlank() && !string(remote.get("@microsoft.graph.downloadUrl")).isBlank()) {
      item.put("@microsoft.graph.downloadUrl", remote.get("@microsoft.graph.downloadUrl"));
    }
    return item;
  }

  private Map<String, Object> enrichRemoteItemForDownload(String accessToken, Map<String, Object> item, String fallbackItemPath) {
    item = mergeRemoteItemMetadata(item);
    if (number(item.get("size")) > 0 && !string(item.get("@microsoft.graph.downloadUrl")).isBlank()) return item;
    var remote = map(item.get("remoteItem"));
    var remoteParent = map(remote.get("parentReference"));
    var remoteDriveId = string(remoteParent.get("driveId"));
    var remoteItemId = string(remote.get("id"));
    if (remoteDriveId.isBlank() || remoteItemId.isBlank()) return item;
    var remotePath = "/drives/" + enc(remoteDriveId) + "/items/" + enc(remoteItemId);
    try {
      var remoteItem = graphGet(accessToken, remotePath);
      var merged = mergeRemoteItemMetadata(remoteItem);
      if (string(merged.get("@microsoft.graph.downloadUrl")).isBlank() && merged.get("file") != null) {
        var redirectUrl = graphContentRedirectUrl(accessToken, remotePath + "/content");
        if (!redirectUrl.isBlank()) merged.put("@microsoft.graph.downloadUrl", redirectUrl);
      }
      if (number(merged.get("size")) > 0 || !string(merged.get("@microsoft.graph.downloadUrl")).isBlank()) return merged;
    } catch (ApiException ignored) {
      // Fall back to the original item; some shared links only expose the wrapper item.
    }
    if (string(item.get("@microsoft.graph.downloadUrl")).isBlank() && item.get("file") != null) {
      var redirectUrl = graphContentRedirectUrl(accessToken, fallbackItemPath + "/content");
      if (!redirectUrl.isBlank()) item.put("@microsoft.graph.downloadUrl", redirectUrl);
    }
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

  private String graphContentRedirectUrl(String accessToken, String path) {
    try {
      var uri = path.startsWith("https://graph.microsoft.com/") ? URI.create(path) : URI.create(GRAPH_ROOT + path);
      var request = HttpRequest.newBuilder(uri)
        .header("Authorization", "Bearer " + accessToken)
        .timeout(Duration.ofSeconds(30))
        .GET()
        .build();
      var response = noRedirectHttpClient.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() >= 300 && response.statusCode() < 400) {
        return response.headers().firstValue("Location").orElse("");
      }
      return "";
    } catch (Exception error) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "OneDrive 下载地址获取失败");
    }
  }

  private Map<String, Object> graphJson(String accessToken, String method, String path, Map<String, Object> body) {
    return graphRequest(accessToken, method, path, body);
  }

  private void graphNoBody(String accessToken, String method, String path) {
    graphRequest(accessToken, method, path, null);
  }

  private String sharedRootItemId(CloudDriveRecord drive, String itemId) {
    var value = string(itemId);
    if ("onedrive-shared".equals(drive.provider()) && (value.isBlank() || "root".equals(value))) return drive.rootItemId();
    return value.isBlank() ? "root" : value;
  }

  private String graphItemPath(CloudDriveRecord drive, String itemId) {
    var target = string(itemId);
    if ("root".equals(target) || target.isBlank()) return "/drives/" + enc(drive.driveId()) + "/root";
    return "/drives/" + enc(drive.driveId()) + "/items/" + enc(target);
  }

  private String graphItemPath(GraphItemRef item) {
    var target = string(item.itemId());
    if ("root".equals(target) || target.isBlank()) return "/drives/" + enc(item.driveId()) + "/root";
    return "/drives/" + enc(item.driveId()) + "/items/" + enc(target);
  }

  private GraphItemRef graphItemRef(CloudDriveRecord drive, String itemId) {
    var value = string(itemId);
    if (value.startsWith("shortcut:")) {
      var shortcutId = value.substring("shortcut:".length());
      var rows = jdbc.queryForList(
        "select * from beiming_cloud_shortcuts where id = ? and user_id = ? and target_drive_id = ?",
        shortcutId, drive.userId(), drive.id()
      );
      if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "快捷方式不存在");
      var row = rows.get(0);
      return new GraphItemRef(string(row.get("source_drive_id")), string(row.get("source_item_id")), string(row.get("name")));
    }
    if (value.startsWith("remote:")) {
      var parts = value.split(":", 3);
      if (parts.length == 3) return new GraphItemRef(decodeRefPart(parts[1]), decodeRefPart(parts[2]), "");
    }
    return new GraphItemRef(drive.driveId(), sharedRootItemId(drive, value), "");
  }

  private String remoteItemId(String driveId, String itemId) {
    return "remote:" + encodeRefPart(driveId) + ":" + encodeRefPart(itemId);
  }

  private String encodeRefPart(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(string(value).getBytes(StandardCharsets.UTF_8));
  }

  private String decodeRefPart(String value) {
    try {
      return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    } catch (Exception error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "远端文件 ID 无效");
    }
  }

  private Map<String, Object> graphParentReference(CloudDriveRecord drive, String parentId) {
    if (parentId == null || parentId.isBlank() || "root".equals(parentId)) {
      if (!drive.rootItemId().isBlank()) return Map.of("driveId", drive.driveId(), "id", drive.rootItemId());
      return Map.of("driveId", drive.driveId(), "path", "/drive/root:");
    }
    var parent = graphItemRef(drive, parentId);
    return Map.of("driveId", parent.driveId(), "id", parent.itemId());
  }

  private String graphAccepted(String accessToken, String method, String path, Map<String, Object> body) {
    try {
      var uri = path.startsWith("https://graph.microsoft.com/") ? URI.create(path) : URI.create(GRAPH_ROOT + path);
      var builder = HttpRequest.newBuilder(uri)
        .header("Authorization", "Bearer " + accessToken)
        .timeout(Duration.ofSeconds(30));
      if (body == null) {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        builder.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
          .header("content-type", "application/json");
      }
      var response = sendWithRetry(builder.build());
      if (response.statusCode() == 202) return response.headers().firstValue("Location").orElse("");
      parseGraphResponse(response, "OneDrive request failed");
      return "";
    } catch (ApiException error) {
      throw error;
    } catch (Exception error) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "OneDrive 请求失败: " + error.getMessage());
    }
  }

  private Map<String, Object> waitGraphCopy(String accessToken, CloudDriveRecord drive, String monitorUrl) {
    if (monitorUrl == null || monitorUrl.isBlank()) return Map.of();
    var deadline = System.currentTimeMillis() + 90_000L;
    while (System.currentTimeMillis() < deadline) {
      var status = graphGet(accessToken, monitorUrl);
      var progress = string(status.get("status"));
      if ("completed".equalsIgnoreCase(progress)) {
        var resource = map(status.get("resourceLocation"));
        var resourceUrl = string(status.get("resourceLocation"));
        if (!resource.isEmpty()) resourceUrl = string(resource.get("url"));
        if (!resourceUrl.isBlank()) return graphGet(accessToken, resourceUrl);
        var resourceId = string(status.get("resourceId"));
        if (!resourceId.isBlank()) return graphGet(accessToken, "/drives/" + enc(drive.driveId()) + "/items/" + enc(resourceId));
        return Map.of();
      }
      if ("failed".equalsIgnoreCase(progress) || "deletePending".equalsIgnoreCase(progress)) {
        var error = map(status.get("error"));
        throw new ApiException(HttpStatus.BAD_GATEWAY, firstNonBlank(string(error.get("message")), "OneDrive 复制失败"));
      }
      try {
        Thread.sleep(700);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new ApiException(HttpStatus.BAD_GATEWAY, "OneDrive 复制被中断");
      }
    }
    throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "OneDrive 复制超时，请稍后刷新目录查看结果");
  }

  private Map<String, Object> graphRequest(String accessToken, String method, String path, Map<String, Object> body) {
    try {
      var uri = path.startsWith("http://") || path.startsWith("https://") ? URI.create(path) : URI.create(GRAPH_ROOT + path);
      var builder = HttpRequest.newBuilder(uri)
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
    Map<String, Object> payload = Map.of();
    if (!body.isBlank() && body.stripLeading().startsWith("{")) {
      payload = mapper.readValue(body, MAP_TYPE);
    }
    if (response.statusCode() >= 200 && response.statusCode() < 300) return payload;
    var error = map(payload.get("error"));
    var message = firstNonBlank(
      string(error.get("message")),
      string(payload.get("error_description")),
      body.length() > 180 ? body.substring(0, 180) : body,
      httpStatusText(response.statusCode()),
      fallback
    );
    throw new ApiException(HttpStatus.BAD_GATEWAY, message);
  }

  private HttpResponse<String> sendWithRetry(HttpRequest request) throws Exception {
    try {
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (!isRetriableGraphStatus(response.statusCode())) return response;
      Thread.sleep(700);
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (!isRetriableGraphStatus(response.statusCode())) return response;
      Thread.sleep(1400);
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

  private boolean isRetriableGraphStatus(int status) {
    return status == 429 || status == 503 || status == 504;
  }

  private String httpStatusText(int status) {
    return switch (status) {
      case 400 -> "400 BAD_REQUEST";
      case 401 -> "401 UNAUTHORIZED";
      case 403 -> "403 FORBIDDEN";
      case 404 -> "404 NOT_FOUND";
      case 409 -> "409 CONFLICT";
      case 429 -> "429 TOO_MANY_REQUESTS";
      case 500 -> "500 INTERNAL_SERVER_ERROR";
      case 503 -> "503 SERVICE_UNAVAILABLE";
      case 504 -> "504 GATEWAY_TIMEOUT";
      default -> status > 0 ? String.valueOf(status) : "";
    };
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

  private String shareId(String url) {
    var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(string(url).getBytes(StandardCharsets.UTF_8));
    return "u!" + encoded;
  }

  private String graphCursor(String nextLink) {
    var text = string(nextLink);
    if (text.isBlank()) return "";
    if (!text.startsWith(GRAPH_ROOT + "/")) return "";
    return text;
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

  private Object firstNonNull(Object... values) {
    for (var value : values) {
      if (value != null) return value;
    }
    return null;
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

  private boolean looksLikeEmail(String value) {
    return string(value).matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
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
