import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  AlertCircle,
  Bell,
  Check,
  Eye,
  LogIn,
  LogOut,
  Flag,
  Heart,
  MessageSquare,
  RefreshCw,
  Save,
  Shield,
  Star,
  Send,
  User,
  Users,
} from 'lucide-react';
import {
  clearToken,
  createCommunityComment,
  createCommunityPost,
  favoriteCommunityPost,
  getCommunityBoards,
  getCommunityComments,
  getCommunityPost,
  getCommunityPosts,
  getAdminMembers,
  getMe,
  getProfileMe,
  getPublicMember,
  getPublicMembers,
  likeCommunityPost,
  login,
  readSession,
  reportCommunityPost,
  archiveNotification,
  getNotificationUnreadCount,
  getNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  unfavoriteCommunityPost,
  unlikeCommunityPost,
  updateAdminMember,
  updateProfileMe,
  voteCommunityPoll,
} from './api.js';
import { Avatar, NotificationPanel, Panel } from './components.jsx';
import './styles.css';

const notificationFilters = ['ALL', 'UNREAD', 'READ', 'ARCHIVED'];
const notificationTypeOptions = [
  '',
  'POST_COMMENTED',
  'COMMENT_REPLIED',
  'POST_LIKED',
  'COMMENT_LIKED',
  'REPORT_REVIEWED',
  'POST_MODERATED',
  'COMMENT_MODERATED',
];

const emptyProfileForm = {
  displayName: '',
  bio: '',
  avatarUrl: '',
  minecraftId: '',
  minecraftUuid: '',
  visibility: 'PUBLIC',
};

const emptyPostForm = {
  boardId: '',
  title: '',
  content: '',
  visibility: 'PUBLIC',
  pollQuestion: '',
  pollOptions: '',
};

function App() {
  const [token, setToken] = useState(readSession().token);
  const [user, setUser] = useState(null);
  const [profile, setProfile] = useState(null);
  const [profileForm, setProfileForm] = useState(emptyProfileForm);
  const [members, setMembers] = useState({ items: [], page: 1, pageSize: 20, total: 0 });
  const [memberQuery, setMemberQuery] = useState('');
  const [selectedMember, setSelectedMember] = useState(null);
  const [adminMembers, setAdminMembers] = useState({ items: [], page: 1, pageSize: 50, total: 0 });
  const [editingAdminProfileId, setEditingAdminProfileId] = useState('');
  const [adminPatch, setAdminPatch] = useState({ memberGroup: 'MEMBER', memberStatus: 'ACTIVE', visibility: 'PUBLIC', featured: false, adminNote: '' });
  const [loginForm, setLoginForm] = useState({ email: '', password: '' });
  const [busy, setBusy] = useState('');
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [boards, setBoards] = useState([]);
  const [postQuery, setPostQuery] = useState({ boardId: '', q: '', sort: 'latest' });
  const [posts, setPosts] = useState({ items: [], page: 1, pageSize: 12, total: 0 });
  const [selectedPost, setSelectedPost] = useState(null);
  const [comments, setComments] = useState({ items: [], page: 1, pageSize: 10, total: 0 });
  const [postForm, setPostForm] = useState(emptyPostForm);
  const [commentText, setCommentText] = useState('');
  const [notificationStatus, setNotificationStatus] = useState('ALL');
  const [notificationType, setNotificationType] = useState('');
  const [notifications, setNotifications] = useState({ items: [], page: 1, pageSize: 20, total: 0 });
  const [unreadCount, setUnreadCount] = useState(0);
  const [highlightedCommentId, setHighlightedCommentId] = useState('');
  const notificationPanelRef = useRef(null);

  const isAdmin = user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN';

  useEffect(() => {
    loadPublicMembers();
    loadCommunity();
  }, []);

  useEffect(() => {
    if (token) {
      loadPrivateState();
      return;
    }
    setNotifications({ items: [], page: 1, pageSize: 20, total: 0 });
    setUnreadCount(0);
  }, [token]);

  useEffect(() => {
    if (token) loadNotifications({ status: notificationStatus, type: notificationType, page: 1 });
  }, [token, notificationStatus, notificationType]);

  useEffect(() => {
    if (!highlightedCommentId) return;
    const target = document.getElementById(`comment-${highlightedCommentId}`);
    if (!target) return;
    target.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [comments, highlightedCommentId]);

  async function run(label, action) {
    setBusy(label);
    setError('');
    setNotice('');
    try {
      const result = await action();
      return result;
    } catch (err) {
      setError(err.message || '请求失败');
      return null;
    } finally {
      setBusy('');
    }
  }

  async function loadPublicMembers(q = memberQuery) {
    return run('members', async () => {
      const data = await getPublicMembers({ q });
      setMembers(data);
      if (!selectedMember && data.items.length > 0) setSelectedMember(data.items[0]);
      return data;
    });
  }

  async function loadCommunity(query = postQuery) {
    return run('community', async () => {
      const [nextBoards, nextPosts] = await Promise.all([
        getCommunityBoards(),
        getCommunityPosts({ ...query, pageSize: 12 }),
      ]);
      setBoards(nextBoards);
      setPosts(nextPosts);
      setPostForm((current) => ({ ...current, boardId: current.boardId || nextBoards[0]?.id || '' }));
      if (!selectedPost && nextPosts.items.length > 0) {
        await openPost(nextPosts.items[0].id);
      }
      return nextPosts;
    });
  }

  async function openPost(postId) {
    return run(`post-${postId}`, async () => {
      const [detail, nextComments] = await Promise.all([
        getCommunityPost(postId),
        getCommunityComments(postId, { pageSize: 10 }),
      ]);
      setSelectedPost(detail);
      setComments(nextComments);
      return detail;
    });
  }

  async function submitPost(event) {
    event.preventDefault();
    const pollOptions = postForm.pollOptions
      .split('\n')
      .map((value) => value.trim())
      .filter(Boolean);
    const body = {
      boardId: postForm.boardId,
      title: postForm.title,
      content: postForm.content,
      status: 'PUBLISHED',
      visibility: postForm.visibility,
    };
    if (postForm.pollQuestion.trim() && pollOptions.length >= 2) {
      body.poll = {
        question: postForm.pollQuestion,
        voteMode: 'SINGLE',
        resultVisibility: 'AFTER_VOTE',
        options: pollOptions.map((text) => ({ text })),
      };
    }
    const data = await run('create-post', () => createCommunityPost(body));
    if (!data) return;
    setPostForm({ ...emptyPostForm, boardId: postForm.boardId });
    setNotice('帖子已发布');
    await loadCommunity(postQuery);
    await openPost(data.id);
  }

  async function submitComment(event) {
    event.preventDefault();
    if (!selectedPost || !commentText.trim()) return;
    const data = await run('create-comment', () => createCommunityComment(selectedPost.id, commentText));
    if (!data) return;
    setCommentText('');
    setNotice('评论已发布');
    const nextComments = await getCommunityComments(selectedPost.id, { pageSize: 10 });
    setComments(nextComments);
    await loadNotifications(notificationStatus);
  }

  async function togglePostLike() {
    if (!selectedPost) return;
    await run('like-post', () => selectedPost.liked ? unlikeCommunityPost(selectedPost.id) : likeCommunityPost(selectedPost.id));
    await openPost(selectedPost.id);
  }

  async function togglePostFavorite() {
    if (!selectedPost) return;
    await run('favorite-post', () => selectedPost.favorited ? unfavoriteCommunityPost(selectedPost.id) : favoriteCommunityPost(selectedPost.id));
    await openPost(selectedPost.id);
  }

  async function reportPost() {
    if (!selectedPost) return;
    const data = await run('report-post', () => reportCommunityPost(selectedPost.id, { reason: 'SPAM', detail: '前端人工测试举报' }));
    if (data) setNotice('举报已提交');
    await loadNotifications(notificationStatus);
  }

  async function votePoll(optionId) {
    if (!selectedPost) return;
    await run('vote-poll', () => voteCommunityPoll(selectedPost.id, [optionId]));
    await openPost(selectedPost.id);
  }

  async function loadPrivateState() {
    return run('private', async () => {
      const [nextUser, nextProfile] = await Promise.all([getMe(), getProfileMe()]);
      setUser(nextUser);
      setProfile(nextProfile);
      setProfileForm({
        displayName: nextProfile.displayName || nextUser.name || '',
        bio: nextProfile.bio || '',
        avatarUrl: nextProfile.avatarUrl || '',
        minecraftId: nextProfile.minecraftId || '',
        minecraftUuid: nextProfile.minecraftUuid || '',
        visibility: nextProfile.visibility || 'PUBLIC',
      });
      if (nextUser.role === 'ADMIN' || nextUser.role === 'SUPER_ADMIN') {
        const data = await getAdminMembers();
        setAdminMembers(data);
      }
    });
  }

  async function loadNotifications(query = {}) {
    return run('notifications', async () => {
      const [nextList, nextCount] = await Promise.all([
        getNotifications({
          status: query.status ?? notificationStatus,
          type: query.type ?? notificationType,
          page: query.page ?? 1,
          pageSize: 20,
        }),
        getNotificationUnreadCount(),
      ]);
      setNotifications(nextList);
      setUnreadCount(nextCount.unreadCount || 0);
      return nextList;
    });
  }

  async function submitLogin(event) {
    event.preventDefault();
    const data = await run('login', () => login(loginForm.email, loginForm.password));
    if (!data) return;
    setToken(data.token);
    setUser(data.user);
    setNotice('已登录');
  }

  function logout() {
    clearToken();
    setToken('');
    setUser(null);
    setProfile(null);
    setProfileForm(emptyProfileForm);
    setAdminMembers({ items: [], page: 1, pageSize: 50, total: 0 });
    setNotifications({ items: [], page: 1, pageSize: 20, total: 0 });
    setUnreadCount(0);
    setNotice('已退出');
  }

  async function saveProfile(event) {
    event.preventDefault();
    const data = await run('save-profile', () => updateProfileMe(profileForm));
    if (!data) return;
    setProfile(data);
    setNotice('成员档案已保存');
    loadPublicMembers();
  }

  async function selectMember(profileId) {
    const data = await run(`member-${profileId}`, () => getPublicMember(profileId));
    if (data) setSelectedMember(data);
  }

  function startAdminEdit(item) {
    setEditingAdminProfileId(item.id);
    setAdminPatch({
      memberGroup: item.memberGroup || 'MEMBER',
      memberStatus: item.memberStatus || 'ACTIVE',
      visibility: item.visibility || 'PUBLIC',
      featured: Boolean(item.featured),
      adminNote: '',
    });
  }

  async function saveAdminPatch(event) {
    event.preventDefault();
    const data = await run('admin-save', () => updateAdminMember(editingAdminProfileId, adminPatch));
    if (!data) return;
    const next = await getAdminMembers();
    setAdminMembers(next);
    setEditingAdminProfileId('');
    setNotice('管理员修改已保存');
    loadPublicMembers();
  }

  async function openNotification(item) {
    if (!item) return;
    if (item.status === 'UNREAD') {
      await run('notification-open', async () => {
        await markNotificationRead(item.id);
        setNotice('通知已标为已读');
      });
    }
    const payload = parseNotificationPayload(item.payloadJson);
    const postId = payload.postId || extractPostId(item.actionUrl);
    const commentId = payload.commentId || payload.parentCommentId || extractCommentId(item.actionUrl);
    if (postId) {
      await openPost(postId);
      if (commentId) {
        await focusComment(postId, commentId);
      } else {
        setHighlightedCommentId('');
      }
    }
    await loadNotifications({ status: notificationStatus, type: notificationType, page: notifications.page || 1 });
  }

  async function markAllRead() {
    const data = await run('notification-read-all', () => markAllNotificationsRead());
    if (!data) return;
    setNotice(`已处理 ${data.updated} 条未读通知`);
    await loadNotifications({ status: notificationStatus, type: notificationType, page: notifications.page || 1 });
  }

  async function archiveOneNotification(notificationId) {
    const data = await run('notification-archive', () => archiveNotification(notificationId));
    if (!data) return;
    setNotice('通知已归档');
    const currentPage = notifications.items.length === 1 && (notifications.page || 1) > 1
      ? (notifications.page || 1) - 1
      : (notifications.page || 1);
    await loadNotifications({ status: notificationStatus, type: notificationType, page: currentPage });
  }

  async function focusComment(postId, commentId) {
    const pageData = await getCommunityComments(postId, { pageSize: 50, commentId });
    setComments(pageData);
    setHighlightedCommentId(pageData.items.some((item) => item.id === commentId) ? commentId : '');
  }

  async function goToNotificationPage(page) {
    if (page < 1) return;
    await loadNotifications({ status: notificationStatus, type: notificationType, page });
  }

  const selectedMeta = useMemo(() => {
    if (!selectedMember) return '';
    return `${selectedMember.memberGroup} · ${selectedMember.memberStatus} · ${selectedMember.visibility}`;
  }, [selectedMember]);

  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">Beiming Console</p>
          <h1>成员档案</h1>
        </div>
        <div className="session">
          {user ? (
            <>
              <button
                className="icon-button notification-button"
                onClick={() => notificationPanelRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
                title="通知中心"
                type="button"
              >
                <Bell size={18} />
                {unreadCount > 0 && <span className="notification-badge">{unreadCount > 99 ? '99+' : unreadCount}</span>}
              </button>
              <span>{user.name}</span>
              <span className="role">{user.role}</span>
              <button className="icon-button" onClick={logout} title="退出登录" type="button">
                <LogOut size={18} />
              </button>
            </>
          ) : (
            <span>游客</span>
          )}
        </div>
      </header>

      {(error || notice) && (
        <section className={error ? 'banner error' : 'banner ok'} role="status">
          {error ? <AlertCircle size={18} /> : <Check size={18} />}
          <span>{error || notice}</span>
        </section>
      )}

      <section className="grid">
        <Panel icon={<LogIn size={18} />} title="登录">
          {user ? (
            <div className="quiet-box">
              <p>{user.email}</p>
              <button onClick={loadPrivateState} type="button">
                <RefreshCw size={16} />
                刷新账户
              </button>
            </div>
          ) : (
            <form className="form" onSubmit={submitLogin}>
              <label>
                邮箱
                <input
                  autoComplete="email"
                  onChange={(event) => setLoginForm({ ...loginForm, email: event.target.value })}
                  placeholder="owner@example.com"
                  value={loginForm.email}
                />
              </label>
              <label>
                密码
                <input
                  autoComplete="current-password"
                  onChange={(event) => setLoginForm({ ...loginForm, password: event.target.value })}
                  placeholder="password"
                  type="password"
                  value={loginForm.password}
                />
              </label>
              <button disabled={busy === 'login'} type="submit">
                <LogIn size={16} />
                登录
              </button>
            </form>
          )}
        </Panel>

        <Panel icon={<User size={18} />} title="我的档案">
          {token ? (
            <form className="form" onSubmit={saveProfile}>
              <label>
                展示昵称
                <input value={profileForm.displayName} onChange={(event) => setProfileForm({ ...profileForm, displayName: event.target.value })} />
              </label>
              <label>
                Minecraft ID
                <input value={profileForm.minecraftId} onChange={(event) => setProfileForm({ ...profileForm, minecraftId: event.target.value })} />
              </label>
              <label>
                UUID
                <input value={profileForm.minecraftUuid} onChange={(event) => setProfileForm({ ...profileForm, minecraftUuid: event.target.value })} />
              </label>
              <label>
                头像 URL
                <input value={profileForm.avatarUrl} onChange={(event) => setProfileForm({ ...profileForm, avatarUrl: event.target.value })} />
              </label>
              <label>
                公开范围
                <select value={profileForm.visibility} onChange={(event) => setProfileForm({ ...profileForm, visibility: event.target.value })}>
                  <option value="PUBLIC">PUBLIC</option>
                  <option value="MEMBER_ONLY">MEMBER_ONLY</option>
                  <option value="PRIVATE">PRIVATE</option>
                </select>
              </label>
              <label className="wide">
                简介
                <textarea value={profileForm.bio} onChange={(event) => setProfileForm({ ...profileForm, bio: event.target.value })} />
              </label>
              <button disabled={busy === 'save-profile'} type="submit">
                <Save size={16} />
                保存档案
              </button>
              {profile && <p className="hint">状态：{profile.exists ? '已创建' : '草稿'}</p>}
            </form>
          ) : (
            <p className="hint">登录后可以创建或更新自己的公开档案。</p>
          )}
        </Panel>
      </section>

      <NotificationPanel
        formatTime={formatTime}
        notificationFilters={notificationFilters}
        notificationPanelRef={notificationPanelRef}
        notificationStatus={notificationStatus}
        notificationType={notificationType}
        notificationTypeOptions={notificationTypeOptions}
        notifications={notifications}
        onArchive={archiveOneNotification}
        onChangeStatus={setNotificationStatus}
        onChangeType={setNotificationType}
        onOpen={openNotification}
        onPageChange={goToNotificationPage}
        onReadAll={markAllRead}
        onRefresh={() => loadNotifications({ status: notificationStatus, type: notificationType, page: notifications.page || 1 })}
        token={token}
        unreadCount={unreadCount}
      />

      <section className="community-layout">
        <Panel icon={<MessageSquare size={18} />} title="社区论坛">
          <form className="search" onSubmit={(event) => { event.preventDefault(); loadCommunity(postQuery); }}>
            <select value={postQuery.boardId} onChange={(event) => setPostQuery({ ...postQuery, boardId: event.target.value })}>
              <option value="">全部分区</option>
              {boards.map((board) => (
                <option key={board.id} value={board.id}>{board.name}</option>
              ))}
            </select>
            <input onChange={(event) => setPostQuery({ ...postQuery, q: event.target.value })} placeholder="搜索帖子标题、内容或作者" value={postQuery.q} />
            <button type="submit">
              <RefreshCw size={16} />
              查询
            </button>
          </form>
          <div className="board-strip">
            {boards.map((board) => (
              <button
                className={postQuery.boardId === board.id ? 'chip active' : 'chip'}
                key={board.id}
                onClick={() => {
                  const next = { ...postQuery, boardId: board.id };
                  setPostQuery(next);
                  loadCommunity(next);
                }}
                type="button"
              >
                {board.name}
              </button>
            ))}
          </div>
          <div className="post-list">
            {posts.items.length === 0 && <p className="hint">暂无帖子。</p>}
            {posts.items.map((item) => (
              <button className="post-row" key={item.id} onClick={() => openPost(item.id)} type="button">
                <span>
                  <strong>{item.title}</strong>
                  <small>{item.authorDisplayName} · 评论 {item.commentCount} · 赞 {item.likeCount}</small>
                </span>
                <small>{formatTime(item.publishedAt)}</small>
              </button>
            ))}
          </div>
        </Panel>

        <Panel icon={<Send size={18} />} title="发帖测试台">
          {token ? (
            <form className="form" onSubmit={submitPost}>
              <label>
                分区
                <select value={postForm.boardId} onChange={(event) => setPostForm({ ...postForm, boardId: event.target.value })}>
                  {boards.map((board) => (
                    <option key={board.id} value={board.id}>{board.name}</option>
                  ))}
                </select>
              </label>
              <label>
                可见性
                <select value={postForm.visibility} onChange={(event) => setPostForm({ ...postForm, visibility: event.target.value })}>
                  <option value="PUBLIC">PUBLIC</option>
                  <option value="MEMBER_ONLY">MEMBER_ONLY</option>
                  <option value="ADMIN_ONLY">ADMIN_ONLY</option>
                </select>
              </label>
              <label className="wide">
                标题
                <input value={postForm.title} onChange={(event) => setPostForm({ ...postForm, title: event.target.value })} />
              </label>
              <label className="wide">
                内容
                <textarea value={postForm.content} onChange={(event) => setPostForm({ ...postForm, content: event.target.value })} />
              </label>
              <label className="wide">
                投票问题，可选
                <input value={postForm.pollQuestion} onChange={(event) => setPostForm({ ...postForm, pollQuestion: event.target.value })} />
              </label>
              <label className="wide">
                投票选项，每行一个
                <textarea value={postForm.pollOptions} onChange={(event) => setPostForm({ ...postForm, pollOptions: event.target.value })} />
              </label>
              <button disabled={busy === 'create-post'} type="submit">
                <Send size={16} />
                发布帖子
              </button>
            </form>
          ) : (
            <p className="hint">登录后可以在这里发帖、评论、点赞、收藏、举报和投票。</p>
          )}
        </Panel>

        <Panel icon={<Eye size={18} />} title="帖子详情">
          {selectedPost ? (
            <article className="post-detail">
              <p className="eyebrow">{selectedPost.authorDisplayName} · {selectedPost.visibility} · {formatTime(selectedPost.publishedAt)}</p>
              <h2>{selectedPost.title}</h2>
              <p>{selectedPost.content}</p>
              <div className="action-row">
                <button onClick={togglePostLike} type="button">
                  <Heart size={16} />
                  {selectedPost.liked ? '取消赞' : '点赞'} {selectedPost.likeCount}
                </button>
                <button onClick={togglePostFavorite} type="button">
                  <Star size={16} />
                  {selectedPost.favorited ? '取消收藏' : '收藏'} {selectedPost.favoriteCount}
                </button>
                <button onClick={reportPost} type="button">
                  <Flag size={16} />
                  举报
                </button>
              </div>
              {selectedPost.poll && (
                <div className="poll-box">
                  <strong>{selectedPost.poll.question}</strong>
                  {selectedPost.poll.options.map((option) => (
                    <button className="poll-option" key={option.id} onClick={() => votePoll(option.id)} type="button">
                      <span>{option.optionText}</span>
                      <small>{selectedPost.poll.resultsVisible ? `${option.voteCount} 票` : '投票后可见'}</small>
                    </button>
                  ))}
                </div>
              )}
            </article>
          ) : (
            <p className="hint">选择一个帖子查看详情。</p>
          )}
        </Panel>

        <Panel icon={<MessageSquare size={18} />} title="评论分页">
          {selectedPost ? (
            <>
              <div className="comment-list">
                {comments.items.map((item) => (
                  <article className={item.id === highlightedCommentId ? 'comment-row highlighted' : 'comment-row'} id={`comment-${item.id}`} key={item.id}>
                    <strong>{item.authorDisplayName}</strong>
                    <p>{item.content}</p>
                    <small>{formatTime(item.createdAt)} · 赞 {item.likeCount}</small>
                  </article>
                ))}
                {comments.items.length === 0 && <p className="hint">还没有评论。</p>}
              </div>
              <p className="hint">第 {comments.page} 页，共 {comments.total} 条</p>
              {token ? (
                <form className="comment-form" onSubmit={submitComment}>
                  <textarea value={commentText} onChange={(event) => setCommentText(event.target.value)} placeholder="写一条评论做功能测试" />
                  <button disabled={busy === 'create-comment'} type="submit">
                    <Send size={16} />
                    评论
                  </button>
                </form>
              ) : (
                <p className="hint">登录后可以发表评论。</p>
              )}
            </>
          ) : (
            <p className="hint">先选择帖子。</p>
          )}
        </Panel>
      </section>

      <section className="members-layout">
        <Panel icon={<Users size={18} />} title="公开成员">
          <form className="search" onSubmit={(event) => { event.preventDefault(); loadPublicMembers(memberQuery); }}>
            <input onChange={(event) => setMemberQuery(event.target.value)} placeholder="搜索昵称或 Minecraft ID" value={memberQuery} />
            <button type="submit">
              <RefreshCw size={16} />
              查询
            </button>
          </form>
          <div className="member-list">
            {members.items.length === 0 && <p className="hint">暂无公开成员。</p>}
            {members.items.map((item) => (
              <button className="member-row" key={item.id} onClick={() => selectMember(item.id)} type="button">
                <Avatar profile={item} />
                <span>
                  <strong>{item.displayName}</strong>
                  <small>{item.minecraftId || '未绑定'}</small>
                </span>
                <Eye size={16} />
              </button>
            ))}
          </div>
        </Panel>

        <Panel icon={<Eye size={18} />} title="成员详情">
          {selectedMember ? (
            <article className="profile-detail">
              <Avatar profile={selectedMember} large />
              <div>
                <h2>{selectedMember.displayName}</h2>
                <p>{selectedMeta}</p>
                <p>{selectedMember.bio || '这个成员还没有写简介。'}</p>
                <dl>
                  <dt>Minecraft ID</dt>
                  <dd>{selectedMember.minecraftId || '未绑定'}</dd>
                  <dt>加入时间</dt>
                  <dd>{formatTime(selectedMember.joinedAt)}</dd>
                </dl>
              </div>
            </article>
          ) : (
            <p className="hint">选择一个公开成员查看详情。</p>
          )}
        </Panel>
      </section>

      {isAdmin && (
        <section className="admin-section">
          <Panel icon={<Shield size={18} />} title="管理员成员管理">
            <div className="admin-list">
              {adminMembers.items.map((item) => (
                <button className="admin-row" key={item.id} onClick={() => startAdminEdit(item)} type="button">
                  <span>
                    <strong>{item.displayName}</strong>
                    <small>{item.memberGroup} · {item.memberStatus} · {item.visibility}</small>
                  </span>
                  <span>{item.featured ? '精选' : '普通'}</span>
                </button>
              ))}
            </div>
            {editingAdminProfileId && (
              <form className="form admin-form" onSubmit={saveAdminPatch}>
                <label>
                  身份组
                  <select value={adminPatch.memberGroup} onChange={(event) => setAdminPatch({ ...adminPatch, memberGroup: event.target.value })}>
                    <option value="MEMBER">MEMBER</option>
                    <option value="TRAINEE">TRAINEE</option>
                    <option value="ADMIN">ADMIN</option>
                  </select>
                </label>
                <label>
                  状态
                  <select value={adminPatch.memberStatus} onChange={(event) => setAdminPatch({ ...adminPatch, memberStatus: event.target.value })}>
                    <option value="ACTIVE">ACTIVE</option>
                    <option value="INACTIVE">INACTIVE</option>
                    <option value="LEFT">LEFT</option>
                    <option value="HIDDEN">HIDDEN</option>
                  </select>
                </label>
                <label>
                  可见性
                  <select value={adminPatch.visibility} onChange={(event) => setAdminPatch({ ...adminPatch, visibility: event.target.value })}>
                    <option value="PUBLIC">PUBLIC</option>
                    <option value="MEMBER_ONLY">MEMBER_ONLY</option>
                    <option value="PRIVATE">PRIVATE</option>
                  </select>
                </label>
                <label className="checkbox">
                  <input checked={adminPatch.featured} onChange={(event) => setAdminPatch({ ...adminPatch, featured: event.target.checked })} type="checkbox" />
                  精选展示
                </label>
                <label className="wide">
                  管理备注
                  <textarea value={adminPatch.adminNote} onChange={(event) => setAdminPatch({ ...adminPatch, adminNote: event.target.value })} />
                </label>
                <button disabled={busy === 'admin-save'} type="submit">
                  <Save size={16} />
                  保存管理字段
                </button>
              </form>
            )}
          </Panel>
        </section>
      )}
    </main>
  );
}

function formatTime(value) {
  if (!value) return '未设置';
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value));
}

function extractPostId(actionUrl) {
  const value = String(actionUrl || '').trim();
  const match = value.match(/\/community\/posts\/([^/?#]+)/);
  return match?.[1] ? decodeURIComponent(match[1]) : '';
}

function extractCommentId(actionUrl) {
  const value = String(actionUrl || '').trim();
  const commentId = new URLSearchParams(value.split('?')[1] || '').get('commentId');
  return commentId ? decodeURIComponent(commentId) : '';
}

function parseNotificationPayload(payloadJson) {
  try {
    const parsed = JSON.parse(payloadJson || '{}');
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

createRoot(document.getElementById('root')).render(<App />);
