import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  AlertCircle,
  Check,
  Eye,
  LogIn,
  LogOut,
  RefreshCw,
  Save,
  Shield,
  User,
  Users,
} from 'lucide-react';
import {
  clearToken,
  getAdminMembers,
  getMe,
  getProfileMe,
  getPublicMember,
  getPublicMembers,
  login,
  readSession,
  updateAdminMember,
  updateProfileMe,
} from './api.js';
import './styles.css';

const emptyProfileForm = {
  displayName: '',
  bio: '',
  avatarUrl: '',
  minecraftId: '',
  minecraftUuid: '',
  visibility: 'PUBLIC',
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

  const isAdmin = user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN';

  useEffect(() => {
    loadPublicMembers();
  }, []);

  useEffect(() => {
    if (token) loadPrivateState();
  }, [token]);

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

function Panel({ children, icon, title }) {
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

function Avatar({ profile, large = false }) {
  const source = profile.avatarUrl || profile.skinUrl;
  return (
    <div className={large ? 'avatar large' : 'avatar'}>
      {source ? <img alt="" src={source} /> : <User size={large ? 34 : 22} />}
    </div>
  );
}

function formatTime(value) {
  if (!value) return '未设置';
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value));
}

createRoot(document.getElementById('root')).render(<App />);
