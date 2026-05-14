export const fallbackNodes = [
  { id: 'amd-9950x', name: 'AMD-9950X - localhost', address: 'localhost' },
  { id: 'amd-7840h', name: 'AMD-7840h - 192.168.1.8', address: '192.168.1.8' },
];

export const fallbackResources = [
  {
    id: 'vm-02',
    name: '代理节点-华东',
    kind: 'Virtual Machine',
    region: '杭州可用区 B',
    plan: '4C / 8G',
    status: '运行中',
    load: 34,
    owner: '许星野',
    endpoint: '10.24.8.12',
    nodeId: 'amd-9950x',
  },
  {
    id: 'ct-07',
    name: 'auth-service',
    kind: 'Container',
    region: '本地 Docker',
    plan: '2C / 2G',
    status: '部署中',
    load: 72,
    owner: '系统',
    endpoint: 'beiming-auth:8080',
    nodeId: 'amd-9950x',
  },
  {
    id: 'vm-09',
    name: '数据库预发机',
    kind: 'Virtual Machine',
    region: 'WSL2 Lab',
    plan: '2C / 4G',
    status: '维护中',
    load: 18,
    owner: '周临川',
    endpoint: '172.22.32.8',
    nodeId: 'amd-7840h',
  },
  {
    id: 'ct-11',
    name: 'mc-proxy',
    kind: 'Container',
    region: '远程 Docker',
    plan: '1C / 1G',
    status: '运行中',
    load: 29,
    owner: '系统',
    endpoint: 'mc-proxy:25577',
    nodeId: 'amd-7840h',
  },
];

export const activityTasks = [
  { title: '容器 auth-service 发布', desc: '镜像 beiming/auth:2026.05.07 已推送', time: '刚刚', tone: 'blue' },
  { title: '容器快照完成', desc: 'Docker 数据卷快照 18.2GB', time: '12 分钟前', tone: 'green' },
  { title: '异常登录被拦截', desc: '来自 183.14.21.8 的密码尝试', time: '36 分钟前', tone: 'blue' },
  { title: '数据库连接池告警恢复', desc: 'PostgreSQL 连接数回落到 42%', time: '1 小时前', tone: 'slate' },
];

export const demoUsers = [
  { name: '林观澜', email: 'admin@beiming.dev', role: '超级管理员', binding: '邮箱 / QQ / Token', status: '正常' },
  { name: '许星野', email: 'ops@beiming.dev', role: '运维管理员', binding: '邮箱 / QQ', status: '正常' },
  { name: '周临川', email: 'finance@beiming.dev', role: '审计成员', binding: '邮箱', status: '待补全' },
  { name: '沈知白', email: 'member@beiming.dev', role: '普通用户', binding: '邮箱 / QQ', status: '冻结' },
];

export function filterByQuery(items, query) {
  if (!query) return items;
  const keyword = query.toLowerCase();
  return items.filter((item) => `${item.name}${item.kind}${item.region}${item.endpoint}`.toLowerCase().includes(keyword));
}

export function mapContainersToResources(items, node) {
  return (items || []).map((item) => ({
    id: item.id,
    name: item.name || item.id?.slice(0, 12) || 'container',
    kind: 'Container',
    region: node.name,
    plan: item.image || 'Docker',
    status: item.state === 'running' ? '运行中' : item.state === 'exited' ? '已停止' : item.state === 'created' ? '待启动' : '部署中',
    load: item.stats?.cpuPercent || 0,
    owner: '节点',
    endpoint: item.ports || item.command || '-',
    nodeId: node.id,
    image: item.image || item.plan || 'Docker',
    command: item.command,
    startedAt: item.startedAt || '',
    finishedAt: item.finishedAt || '',
    stats: item.stats || {},
    network: item.network || {},
    config: item.config || {},
    restartPolicy: item.restartPolicy || 'no',
    terminal: item.terminal || {},
    raw: item,
  }));
}

export function mapVmsToResources(items, node) {
  return (items || []).map((item) => ({
    id: item.name,
    name: item.name,
    kind: 'Virtual Machine',
    region: node.name,
    plan: 'libvirt / virsh',
    status: item.state?.includes('running') ? '运行中' : item.state?.includes('shut') ? '维护中' : '部署中',
    load: item.state?.includes('running') ? 24 : 0,
    owner: '节点',
    endpoint: item.id ? `domain ${item.id}` : item.state,
    nodeId: node.id,
  }));
}
