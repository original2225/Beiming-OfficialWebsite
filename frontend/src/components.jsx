import React from 'react';
import { Bell, Check, Flag, RefreshCw, User } from 'lucide-react';

export function Panel({ children, icon, title }) {
  return (
    <section className="panel">
      <header className="panel-title">
        {icon}
        <h2>{title}</h2>
      </header>
      {children}
    </section>
  );
}

export function Avatar({ profile, large = false }) {
  const source = profile.avatarUrl || profile.skinUrl;
  return (
    <div className={large ? 'avatar large' : 'avatar'}>
      {source ? <img alt="" src={source} /> : <User size={large ? 34 : 22} />}
    </div>
  );
}

export function NotificationPanel({
  token,
  notificationPanelRef,
  unreadCount,
  notificationFilters,
  notificationTypeOptions,
  notificationStatus,
  notificationType,
  notifications,
  onChangeStatus,
  onChangeType,
  onRefresh,
  onReadAll,
  onOpen,
  onArchive,
  onPageChange,
  formatTime,
}) {
  const totalPages = Math.max(1, Math.ceil((notifications.total || 0) / (notifications.pageSize || 20)));

  return (
    <section className="notification-layout" ref={notificationPanelRef}>
      <Panel icon={<Bell size={18} />} title="站内通知">
        {token ? (
          <>
            <div className="notification-toolbar">
              <div className="notification-summary">
                <strong>{unreadCount}</strong>
                <span>未读提醒</span>
              </div>
              <div className="notification-actions">
                <select value={notificationStatus} onChange={(event) => onChangeStatus(event.target.value)}>
                  {notificationFilters.map((item) => (
                    <option key={item} value={item}>{item}</option>
                  ))}
                </select>
                <select value={notificationType} onChange={(event) => onChangeType(event.target.value)}>
                  <option value="">全部类型</option>
                  {notificationTypeOptions.filter(Boolean).map((item) => (
                    <option key={item} value={item}>{item}</option>
                  ))}
                </select>
                <button onClick={onRefresh} type="button">
                  <RefreshCw size={16} />
                  刷新
                </button>
                <button onClick={onReadAll} type="button">
                  <Check size={16} />
                  全部已读
                </button>
              </div>
            </div>
            <div className="notification-list">
              {notifications.items.length === 0 && <p className="hint">当前筛选下还没有通知。</p>}
              {notifications.items.map((item) => (
                <article className={item.status === 'UNREAD' ? 'notification-row unread' : 'notification-row'} key={item.id}>
                  <button className="notification-main" onClick={() => onOpen(item)} type="button">
                    <span className="notification-head">
                      <strong>{item.title}</strong>
                      <small>{item.type}</small>
                    </span>
                    <p>{item.body}</p>
                    <span className="notification-meta">
                      <small>{item.actorDisplayName || '系统'} · {formatTime(item.createdAt)}</small>
                      <small>{item.status}{item.targetType ? ` · ${item.targetType}` : ''}</small>
                    </span>
                  </button>
                  <button className="notification-archive" onClick={() => onArchive(item.id)} type="button">
                    <Flag size={16} />
                    归档
                  </button>
                </article>
              ))}
            </div>
            <div className="notification-pagination">
              <button disabled={(notifications.page || 1) <= 1} onClick={() => onPageChange((notifications.page || 1) - 1)} type="button">
                上一页
              </button>
              <span>第 {notifications.page || 1} 页，共 {totalPages} 页</span>
              <button disabled={(notifications.page || 1) >= totalPages} onClick={() => onPageChange((notifications.page || 1) + 1)} type="button">
                下一页
              </button>
            </div>
          </>
        ) : (
          <p className="hint">登录后会看到站内通知、未读数和跳转入口。</p>
        )}
      </Panel>
    </section>
  );
}
