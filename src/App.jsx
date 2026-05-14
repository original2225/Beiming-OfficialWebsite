import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import '@xterm/xterm/css/xterm.css';
import {
  Activity,
  AlertCircle,
  Box,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  CircleUserRound,
  Cloud,
  Clipboard,
  Container,
  Copy,
  Cpu,
  Database,
  Download,
  ArrowLeft,
  FileText,
  Folder,
  Gauge,
  HardDrive,
  KeyRound,
  Layers3,
  LayoutDashboard,
  Link2,
  LockKeyhole,
  Mail,
  MessageCircle,
  MonitorCog,
  MoreHorizontal,
  Network,
  PackageOpen,
  Minus,
  PencilLine,
  Plus,
  Power,
  RotateCw,
  Search,
  Server,
  ServerCog,
  Settings2,
  ShieldCheck,
  Square,
  Scissors,
  TerminalSquare,
  Trash2,
  TriangleAlert,
  Upload,
  UsersRound,
  X,
  Zap,
} from 'lucide-react';
import { beaconCleanupContainerUploads, cancelContainerFileDownload, cleanupContainerUploads, copyContainerFile, createContainer, createContainerFileEntry, createNode, deleteContainer, deleteContainerFile, deleteNode, extractContainerFile, fetchContainer, fetchContainerFileContent, fetchContainerFileDownloadInfo, fetchContainerFiles, fetchContainers, fetchContainerStats, fetchImages, fetchNodeMetrics, fetchNodes, fetchVms, operateContainer, pingNode, renameContainerFile, searchDockerImages, streamContainerFileRange, updateContainer, updateNode, uploadContainerFileChunkBinary } from './api.js';
import {
  bytesToMemoryInput,
  editRowsToEnvSpecs,
  editRowsToMountSpecs,
  editRowsToPortSpecs,
  encodeConsoleColor,
  formatBytes,
  formatContainerPorts,
  formatFileTime,
  formatHostPort,
  formatPortTitle,
  formatRate,
  formatRestartPolicy,
  getDockerHubLogoUrl,
  getDownloadPlan,
  getUploadPlan,
  joinContainerPath,
  keyValueStringsToRows,
  makeEditId,
  mountStringsToRows,
  nextNewEntryName,
  normalizeContainerPath,
  normalizeRect,
  parseContainerPort,
  parseDockerRunCommand,
  portsToEditRows,
  rectsIntersect,
  renderTerminalOutput,
  saveBlobFile,
  splitLines,
} from './domain-utils.js';
import { activityTasks, demoUsers, fallbackNodes, fallbackResources, filterByQuery, mapContainersToResources, mapVmsToResources } from './resource-model.js';

const logo = '/beiming-logo.webp';
const SOCKET_BASE_URL = import.meta.env.VITE_SOCKET_BASE_URL || import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8788';
const CONTAINER_CACHE_PREFIX = 'beiming:containers:';
const CONTAINER_DETAIL_CACHE_PREFIX = 'beiming:container:';
const CONTAINER_CACHE_TTL = 5 * 60 * 1000;
const CONTAINER_STATE_GUARD_TTL = 2500;
const MonacoEditor = lazy(() => import('@monaco-editor/react'));
const preloadTerminalModules = () => Promise.all([
  import('socket.io-client'),
  import('@xterm/xterm'),
  import('@xterm/addon-fit'),
]).catch(() => {});

const session = {
  name: '林观澜',
  email: 'admin@beiming.dev',
  role: '超级管理员',
};

const navGroups = [
  {
    title: '资源',
    items: [
      { id: 'overview', label: '概览', icon: LayoutDashboard },
      { id: 'vm', label: '虚拟机', icon: MonitorCog },
      { id: 'containers', label: '容器', icon: Container },
    ],
  },
  {
    title: '平台',
    items: [
      { id: 'network', label: '网络与存储', icon: Network },
      { id: 'identity', label: '用户与绑定', icon: UsersRound },
      { id: 'security', label: '安全审计', icon: ShieldCheck },
    ],
  },
];

const accounts = [
  { provider: '邮箱', value: 'admin@beiming.dev', status: '已验证', icon: Mail },
  { provider: 'QQ', value: '284****920', status: '已绑定', icon: MessageCircle },
  { provider: 'Daemon Token', value: '本地保存', status: '已配置', icon: KeyRound },
];

function App() {
  const initialRoute = readRouteState();
  const [activeView, setActiveViewState] = useState(initialRoute.view);
  const [query, setQuery] = useState('');
  const [nodes, setNodes] = useState(fallbackNodes);
  const [activeNodeId, setActiveNodeIdState] = useState(initialRoute.nodeId || fallbackNodes[0].id);
  const [routeContainerId, setRouteContainerId] = useState(initialRoute.containerId);
  const [remoteResources, setRemoteResources] = useState(() => {
    if (initialRoute.view !== 'containers') return [];
    const nodeId = initialRoute.nodeId || fallbackNodes[0].id;
    const node = fallbackNodes.find((item) => item.id === nodeId) || fallbackNodes[0];
    const cachedDetail = initialRoute.containerId ? readCachedContainer(nodeId, initialRoute.containerId) : null;
    if (cachedDetail) return mapContainersToResources([cachedDetail], node);
    return mapContainersToResources(readCachedContainers(nodeId), node);
  });
  const [remoteLoading, setRemoteLoading] = useState(false);
  const [remoteError, setRemoteError] = useState('');
  const [reloadKey, setReloadKey] = useState(0);
  const [toasts, setToasts] = useState([]);
  const remoteRequestSeq = useRef(0);
  const resourceScopeRef = useRef('');
  const remoteResourcesRef = useRef(remoteResources);
  const containerStateGuardRef = useRef(new Map());
  const activeNode = nodes.find((node) => node.id === activeNodeId) || nodes[0] || fallbackNodes[0];
  useEffect(() => {
    remoteResourcesRef.current = remoteResources;
  }, [remoteResources]);
  const mergeContainerResources = (nextResources) => {
    if (activeView !== 'containers') return nextResources;
    const now = Date.now();
    return nextResources.map((item) => {
      const guard = containerStateGuardRef.current.get(item.id) || containerStateGuardRef.current.get(item.name);
      if (!guard || guard.expiresAt < now) {
        if (guard) containerStateGuardRef.current.delete(item.id);
        return item;
      }
      const itemStartedAt = item.raw?.startedAt || item.startedAt || '';
      const itemFinishedAt = item.raw?.finishedAt || item.finishedAt || '';
      const guardStartedAt = guard.container.raw?.startedAt || guard.container.startedAt || '';
      const guardFinishedAt = guard.container.raw?.finishedAt || guard.container.finishedAt || '';
      const sameGeneration = itemStartedAt === guardStartedAt && itemFinishedAt === guardFinishedAt;
      if (!sameGeneration || item.status !== guard.container.status) return guard.container;
      containerStateGuardRef.current.delete(item.id);
      return item;
    });
  };
  const preserveContainerStats = (nextResources) => {
    if (activeView !== 'containers') return nextResources;
    const current = remoteResourcesRef.current;
    const currentById = new Map(current.map((item) => [item.id, item]));
    const currentByName = new Map(current.map((item) => [item.name, item]));
    return nextResources.map((item) => {
      const previous = currentById.get(item.id) || currentByName.get(item.name);
      if (!previous?.stats) return item;
      const hasIncomingStats = Number(item.stats?.cpuPercent || 0) > 0
        || Number(item.stats?.memoryUsedBytes || 0) > 0
        || Number(item.stats?.memoryLimitBytes || 0) > 0
        || Number(item.stats?.swapUsedBytes || 0) > 0
        || Number(item.stats?.networkRxBytes || 0) > 0
        || Number(item.stats?.networkTxBytes || 0) > 0;
      if (hasIncomingStats) return item;
      return {
        ...item,
        load: previous.load,
        stats: previous.stats,
        raw: { ...(item.raw || {}), stats: previous.stats },
      };
    });
  };
  const cacheContainerResources = (nodeId, resources) => {
    if (!nodeId || !Array.isArray(resources)) return;
    writeCachedContainers(nodeId, resources.map((item) => item.raw || item));
  };
  const hydrateContainerResourcesFromCache = (nodeId = activeNodeId, node = activeNode) => {
    if (!nodeId || !node) return false;
    const cached = readCachedContainers(nodeId);
    if (cached.length === 0) return false;
    setRemoteResources(mapContainersToResources(cached, node));
    setRemoteError('');
    return true;
  };
  const setActiveView = (view) => {
    if (view === 'containers') {
      hydrateContainerResourcesFromCache(activeNodeId, activeNode);
    } else if (view === 'vm') {
      setRemoteResources([]);
    }
    setActiveViewState(view);
    setRouteContainerId('');
    updateRouteState({ view, nodeId: activeNodeId, containerId: '' });
  };
  const setActiveNodeId = (nodeId) => {
    setActiveNodeIdState(nodeId);
    setRouteContainerId('');
    updateRouteState({ view: activeView, nodeId, containerId: '' });
  };

  useEffect(() => {
    let ignore = false;
    fetchNodes()
      .then((nextNodes) => {
        if (ignore || !Array.isArray(nextNodes) || nextNodes.length === 0) return;
        setNodes(nextNodes);
        setActiveNodeIdState((current) => {
          const nextNodeId = nextNodes.some((node) => node.id === current) ? current : nextNodes[0].id;
          if (initialRoute.view === 'containers') {
            const nextNode = nextNodes.find((node) => node.id === nextNodeId) || nextNodes[0];
            const cached = readCachedContainers(nextNodeId);
            if (cached.length > 0) setRemoteResources(mapContainersToResources(cached, nextNode));
          }
          return nextNodeId;
        });
      })
      .catch(() => {
        if (!ignore) setNodes(fallbackNodes);
      });
    return () => {
      ignore = true;
    };
  }, []);

  useEffect(() => {
    if (!['vm', 'containers'].includes(activeView) || !activeNode?.id) return;
    if (activeView === 'containers' && routeContainerId && remoteResources.length === 0) return;
    const requestId = remoteRequestSeq.current + 1;
    remoteRequestSeq.current = requestId;
    const scope = `${activeView}:${activeNode.id}`;
    const isNewScope = resourceScopeRef.current !== scope;
    resourceScopeRef.current = scope;
    let hasWarmSnapshot = remoteResources.length > 0;
    if (isNewScope) {
      if (activeView === 'containers') {
        hasWarmSnapshot = hydrateContainerResourcesFromCache(activeNode.id, activeNode);
        if (!hasWarmSnapshot) setRemoteResources([]);
      } else {
        hasWarmSnapshot = false;
        setRemoteResources([]);
      }
    }
    setRemoteLoading(!hasWarmSnapshot);
    setRemoteError('');
    const loader = activeView === 'containers' ? fetchContainers : fetchVms;
    loader(activeNode.id, activeView === 'containers' && isNewScope ? { fast: true } : undefined)
      .then((items) => {
        if (remoteRequestSeq.current !== requestId) return;
        const mapped = activeView === 'containers'
          ? mapContainersToResources(items, activeNode)
          : mapVmsToResources(items, activeNode);
        const nextResources = mergeContainerResources(preserveContainerStats(mapped));
        if (activeView === 'containers') cacheContainerResources(activeNode.id, nextResources);
        setRemoteResources(nextResources);
      })
      .catch((error) => {
        if (remoteRequestSeq.current !== requestId) return;
        setRemoteResources([]);
        setRemoteError(error.message);
      })
      .finally(() => {
        if (remoteRequestSeq.current === requestId) setRemoteLoading(false);
      });
  }, [activeNode?.id, activeView, reloadKey, routeContainerId, remoteResources.length]);

  useEffect(() => {
    if (activeView !== 'containers' || !activeNode?.id) return;
    if (routeContainerId && remoteResources.length === 0) return;
    const cached = readCachedContainers(activeNode.id);
    if (cached.length > 0) {
      setRemoteResources((current) => current.length > 0 ? current : mapContainersToResources(cached, activeNode));
    }
    let ignore = false;
    let statsRunning = false;
    let listRunning = false;
    const mergeStats = (statsItems = []) => {
      const mappedStats = mapContainersToResources(statsItems, activeNode);
      if (mappedStats.length === 0) return;
      const statsById = new Map(mappedStats.map((item) => [item.id, item]));
      const statsByName = new Map(mappedStats.map((item) => [item.name, item]));
      setRemoteResources((current) => {
        const merged = current.map((item) => {
          const latest = statsById.get(item.id) || statsByName.get(item.name);
          if (!latest) return item;
          return {
            ...item,
            status: latest.status,
            load: latest.load,
            stats: latest.stats,
            startedAt: latest.startedAt || item.startedAt,
            finishedAt: latest.finishedAt || item.finishedAt,
            raw: { ...(item.raw || {}), ...(latest.raw || {}), stats: latest.stats },
          };
        });
        cacheContainerResources(activeNode.id, merged);
        return merged;
      });
    };
    const loadContainerStats = async () => {
      if (statsRunning) return;
      statsRunning = true;
      try {
        const items = await fetchContainerStats(activeNode.id);
        if (ignore) return;
        mergeStats(items);
        setRemoteError('');
      } catch {
        // Stats refresh is opportunistic; keep the visible container list stable.
      } finally {
        statsRunning = false;
      }
    };
    const loadLiveContainers = async () => {
      if (listRunning) return;
      listRunning = true;
      try {
        const items = await fetchContainers(activeNode.id, { fast: true });
        if (ignore) return;
        const nextResources = mergeContainerResources(preserveContainerStats(mapContainersToResources(items, activeNode)));
        cacheContainerResources(activeNode.id, nextResources);
        setRemoteResources(nextResources);
        setRemoteError('');
      } catch {
        // Keep the last good container snapshot during transient refresh failures.
      } finally {
        listRunning = false;
      }
    };
    const statsTimer = setInterval(loadContainerStats, 1000);
    const listTimer = setInterval(loadLiveContainers, 5000);
    loadContainerStats();
    loadLiveContainers();
    return () => {
      ignore = true;
      clearInterval(statsTimer);
      clearInterval(listTimer);
    };
  }, [activeNode?.id, activeView, routeContainerId, remoteResources.length]);

  const filteredResources = useMemo(() => {
    return fallbackResources.filter((item) => `${item.name}${item.kind}${item.region}`.toLowerCase().includes(query.toLowerCase()));
  }, [query]);

  const scopedResources = useMemo(() => {
    return filteredResources.filter((item) => {
      if (item.kind === 'Minecraft') return true;
      return item.nodeId === activeNodeId;
    });
  }, [filteredResources, activeNodeId]);

  const notify = (toast) => {
    const id = `${Date.now()}-${Math.random()}`;
    setToasts((current) => [...current, { id, type: 'success', ...toast }]);
    setTimeout(() => {
      setToasts((current) => current.filter((item) => item.id !== id));
    }, toast.duration || 3200);
  };

  return (
    <div className="shell">
      <ProductNav
        activeNode={activeNode}
        activeView={activeView}
        nodes={nodes}
        query={query}
        setActiveNodeId={setActiveNodeId}
        setActiveView={setActiveView}
        setQuery={setQuery}
      />
      <main className="main">
        {activeView === 'overview' && <Overview resources={scopedResources} activeNode={activeNode} />}
        {activeView === 'vm' && <ResourceCenter title="虚拟机" type="Virtual Machine" resources={filterByQuery(remoteResources, query)} activeNode={activeNode} loading={remoteLoading} error={remoteError} notify={notify} onRetry={() => {
          setReloadKey((value) => value + 1);
          notify({ title: '已重新读取虚拟机', message: activeNode?.name });
        }} />}
        {activeView === 'containers' && <ResourceCenter title="容器" type="Container" resources={filterByQuery(remoteResources, query)} activeNode={activeNode} loading={remoteLoading} error={remoteError} variant="containers" notify={notify} initialContainerId={routeContainerId} onContainerSnapshot={(container) => setRemoteResources((current) => {
          const guard = { container, expiresAt: Date.now() + CONTAINER_STATE_GUARD_TTL };
          containerStateGuardRef.current.set(container.id, guard);
          containerStateGuardRef.current.set(container.name, guard);
          const index = current.findIndex((item) => item.id === container.id || item.name === container.name);
          if (index < 0) return current.length > 0 ? [container, ...current] : [container];
          return current.map((item, itemIndex) => (itemIndex === index ? container : item));
        })} onContainerRouteChange={(containerId) => {
          const nextContainerId = containerId || '';
          setRouteContainerId(nextContainerId);
          updateRouteState({ view: 'containers', nodeId: activeNode?.id, containerId: nextContainerId });
        }} onRetry={() => {
          setReloadKey((value) => value + 1);
        }} />}
        {activeView === 'nodes' && <RemoteNodesView nodes={nodes} notify={notify} onNodesChange={setNodes} />}
        {activeView === 'network' && <NetworkView />}
        {activeView === 'identity' && <IdentityView />}
        {activeView === 'security' && <SecurityView />}
      </main>
      <ToastHost toasts={toasts} onClose={(id) => setToasts((current) => current.filter((item) => item.id !== id))} />
    </div>
  );
}

function ToastHost({ toasts, onClose }) {
  return (
    <div className="toast-host" aria-live="polite">
      {toasts.map((toast) => {
        const Icon = toast.type === 'error' || toast.type === 'warning' ? TriangleAlert : CheckCircle2;
        return (
          <div className={`toast ${toast.type}`} key={toast.id}>
            <Icon size={25} />
            <div>
              <strong>{toast.title}</strong>
              {toast.message && <span>{toast.message}</span>}
            </div>
            <button aria-label="关闭通知" onClick={() => onClose(toast.id)} type="button"><X size={15} /></button>
          </div>
        );
      })}
    </div>
  );
}

function useBodyScrollLock() {
  useEffect(() => {
    const previousOverflow = document.body.style.overflow;
    const previousPaddingRight = document.body.style.paddingRight;
    const scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;
    document.body.style.overflow = 'hidden';
    if (scrollbarWidth > 0) {
      document.body.style.paddingRight = `${scrollbarWidth}px`;
    }
    return () => {
      document.body.style.overflow = previousOverflow;
      document.body.style.paddingRight = previousPaddingRight;
    };
  }, []);
}

function ProductNav({ activeNode, activeView, nodes, query, setActiveNodeId, setActiveView, setQuery }) {
  const items = navGroups[0].items;
  const [nodeOpen, setNodeOpen] = useState(false);
  const nodeSwitcherRef = useRef(null);

  useEffect(() => {
    if (!nodeOpen) return undefined;
    const closeNodeMenu = (event) => {
      if (nodeSwitcherRef.current && !nodeSwitcherRef.current.contains(event.target)) {
        setNodeOpen(false);
      }
    };
    document.addEventListener('mousedown', closeNodeMenu);
    return () => document.removeEventListener('mousedown', closeNodeMenu);
  }, [nodeOpen]);

  return (
    <header className="product-nav">
      <div className="brand">
        <img src={logo} alt="北冥" />
        <div>
          <strong>北冥云</strong>
          <span>资源控制台</span>
        </div>
      </div>
      <nav className="nav-tabs">
        {items.map((item) => {
          const Icon = item.icon;
          return (
            <button className={activeView === item.id ? 'nav-tab active' : 'nav-tab'} key={item.id} onClick={() => setActiveView(item.id)}>
              <Icon size={16} />
              <span>{item.label}</span>
            </button>
          );
        })}
      </nav>
      <label className="global-search">
        <Search size={17} />
        <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索实例、容器、服务器、用户" />
      </label>
      <div className="node-switcher" ref={nodeSwitcherRef}>
        <button className="node-button" onClick={() => setNodeOpen(!nodeOpen)} type="button">
          <span>{activeNode.name}</span>
          <ChevronDown size={18} />
        </button>
        {nodeOpen && (
          <div className="node-menu">
            {nodes.map((node) => (
              <button
                className={node.id === activeNode.id ? 'node-option active' : 'node-option'}
                key={node.id}
                onClick={() => {
                  setActiveNodeId(node.id);
                  setNodeOpen(false);
                }}
                type="button"
              >
                <ServerCog size={18} />
                <span>{node.name}</span>
              </button>
            ))}
            <button
              className="node-manage"
              onClick={() => {
                setActiveView('nodes');
                setNodeOpen(false);
              }}
              type="button"
            >
              <PencilLine size={18} />
              <span>管理远程节点</span>
            </button>
          </div>
        )}
      </div>
      <div className="account-chip">
        <div className="mini-avatar">{session.name.slice(0, 1)}</div>
        <div>
          <strong>{session.name}</strong>
          <span>{session.role}</span>
        </div>
        <MoreHorizontal size={18} />
      </div>
    </header>
  );
}

function Overview({ resources, activeNode }) {
  const running = resources.filter((item) => item.status === '运行中').length;
  return (
    <section className="page">
      <div className="hero">
        <div>
          <h1>统一管理虚拟机、容器与账号绑定</h1>
          <p>当前节点：{activeNode.name}。虚拟机与容器跟随右上角 daemon 节点切换。</p>
          <div className="hero-actions">
            <button className="primary"><Plus size={17} />创建实例</button>
            <button className="secondary"><TerminalSquare size={17} />打开控制台</button>
          </div>
        </div>
        <div className="hero-board">
          <div className="board-head">
            <span>北冥调度器</span>
            <b>ONLINE</b>
          </div>
          <div className="board-grid">
            <Signal label="MC" value="16 ms" />
            <Signal label="VM" value="34%" />
            <Signal label="CT" value="7/8" />
            <Signal label="DB" value="42%" />
          </div>
          <div className="wave-line"></div>
        </div>
      </div>

      <div className="metric-grid">
        <Metric icon={Server} label="实例总数" value={resources.length} trend="+3 本周" />
        <Metric icon={Activity} label="运行中" value={running} trend="SLA 99.96%" />
        <Metric icon={Cpu} label="CPU 使用率" value="46%" trend="峰值 72%" />
        <Metric icon={Database} label="数据库" value="Postgres" trend="等待连接" />
      </div>

      <div className="content-grid">
        <Panel title="资源实例" action="查看全部" icon={Cloud}>
          <ResourceTable resources={resources} />
        </Panel>
        <Panel title="最近事件" action="审计日志" icon={Gauge}>
          <ActivityFeed />
        </Panel>
      </div>

      <div className="content-grid compact">
        <Panel title="账号绑定" action="管理绑定" icon={Link2}>
          <AccountBindings />
        </Panel>
        <Panel title="容量与配额" action="调整配额" icon={HardDrive}>
          <QuotaPanel />
        </Panel>
      </div>
    </section>
  );
}

function ResourceCenter({ title, type, resources, activeNode, loading = false, error = '', onRetry, variant = 'table', notify, initialContainerId = '', onContainerRouteChange, onContainerSnapshot }) {
  const scoped = resources.filter((item) => item.kind === type);
  const [activeContainer, setActiveContainer] = useState(() => {
    if (variant !== 'containers' || !initialContainerId) return null;
    return scoped.find((item) => item.id === initialContainerId || item.name === initialContainerId) || null;
  });
  const [creatingContainer, setCreatingContainer] = useState(false);
  const restoredContainerRouteRef = useRef(false);
  const restoringContainerFetchRef = useRef('');
  const isContainerView = variant === 'containers';
  const showBlockingLoading = loading && scoped.length === 0;
  const scopedActiveContainer = activeContainer ? scoped.find((item) => item.id === activeContainer.id) : null;
  const currentContainer = activeContainer ? activeContainer : null;
  const shouldRestoreContainer = isContainerView && initialContainerId && !activeContainer && !restoredContainerRouteRef.current;
  useEffect(() => {
    if (isContainerView && activeContainer && initialContainerId) {
      restoredContainerRouteRef.current = true;
    }
  }, [activeContainer, initialContainerId, isContainerView]);
  useEffect(() => {
    if (!activeContainer || !isContainerView) return;
    const latest = scoped.find((item) => item.id === activeContainer.id)
      || scoped.find((item) => item.name === activeContainer.name);
    if (latest && latest !== activeContainer) {
      setActiveContainer(latest);
    }
  }, [activeContainer, isContainerView, scoped]);
  useEffect(() => {
    if (!activeContainer || !isContainerView) return;
    onContainerRouteChange?.(activeContainer.id || activeContainer.name);
  }, [activeContainer?.id, activeContainer?.name, isContainerView]);
  useEffect(() => {
    if (!isContainerView || !initialContainerId || activeContainer || restoredContainerRouteRef.current) return;
    const latest = scoped.find((item) => item.id === initialContainerId || item.name === initialContainerId);
    if (latest) {
      restoredContainerRouteRef.current = true;
      setActiveContainer(latest);
      return;
    }
    const restoreFetchKey = `${activeNode?.id || ''}:${initialContainerId}`;
    if (activeNode?.id && resources.length === 0 && !error && restoringContainerFetchRef.current !== restoreFetchKey) {
      let cancelled = false;
      restoringContainerFetchRef.current = restoreFetchKey;
      fetchContainer(activeNode.id, initialContainerId, { fast: true })
        .then((item) => {
          if (cancelled || restoredContainerRouteRef.current) return;
          const [mapped] = mapContainersToResources([item], activeNode);
          if (mapped) {
            restoredContainerRouteRef.current = true;
            writeCachedContainer(activeNode.id, item);
            onContainerSnapshot?.(mapped);
            setActiveContainer(mapped);
          }
        })
        .catch(() => {});
      return () => {
        cancelled = true;
      };
    }
    if (!loading && !error && resources.length > 0) {
      restoredContainerRouteRef.current = true;
      onContainerRouteChange?.('');
    }
  }, [activeContainer, activeNode, error, initialContainerId, isContainerView, loading, resources.length, scoped]);
  const openContainer = (container) => {
    if (activeNode?.id) writeCachedContainer(activeNode.id, container.raw || container);
    setActiveContainer(container);
    onContainerRouteChange?.(container.id || container.name);
  };
  const backToList = () => {
    restoredContainerRouteRef.current = true;
    setActiveContainer(null);
    onContainerRouteChange?.('');
  };
  const syncActiveContainer = async (preferredName, latestItem = null) => {
    if (!activeNode?.id || !activeContainer) return;
    if (latestItem) {
      syncContainerSnapshot(latestItem);
      setTimeout(() => onRetry?.(), 80);
      return;
    }
    const items = await fetchContainers(activeNode.id);
    const mapped = mapContainersToResources(items, activeNode);
    const latest = mapped.find((item) => item.name === preferredName)
      || mapped.find((item) => item.id === activeContainer.id)
      || mapped.find((item) => item.name === activeContainer.name);
    if (latest) setActiveContainer(latest);
    setTimeout(() => onRetry?.(), 80);
  };
  const syncContainerSnapshot = (item) => {
    if (!activeNode?.id || !item) return null;
    const [mapped] = mapContainersToResources([item], activeNode);
    if (!mapped) return null;
    writeCachedContainer(activeNode.id, item.raw || item);
    onContainerSnapshot?.(mapped);
    setActiveContainer(mapped);
    return mapped;
  };
  if (isContainerView && currentContainer) {
    return (
      <section className="page">
        <ContainerDetailPage
          container={currentContainer}
          node={activeNode}
          notify={notify}
          onBack={backToList}
          onRefresh={onRetry}
          onContainerUpdate={syncContainerSnapshot}
          onSaved={syncActiveContainer}
        />
      </section>
    );
  }
  if (shouldRestoreContainer) {
    return (
      <section className="page">
        <ContainerRouteRestoring
          activeNode={activeNode}
          containerId={initialContainerId}
          error={error}
          loading={loading}
          onBack={() => {
            restoredContainerRouteRef.current = true;
            onContainerRouteChange?.('');
          }}
          onRetry={onRetry}
        />
      </section>
    );
  }
  return (
    <section className="page">
      <PageHead title={title} desc={`当前共享节点：${activeNode?.name || 'Daemon'}。集中查看和管理 ${title} 实例、状态、入口与负责人。`} action={isContainerView ? '创建容器' : '创建资源'} onAction={isContainerView ? () => setCreatingContainer(true) : undefined} />
      <div className="metric-grid three">
        <Metric icon={Box} label="实例" value={scoped.length} trend="当前筛选" />
        <Metric icon={Zap} label="平均负载" value={`${Math.round(scoped.reduce((sum, item) => sum + item.load, 0) / Math.max(scoped.length, 1))}%`} trend="自动计算" />
        <Metric icon={CheckCircle2} label="健康状态" value={scoped.some((item) => item.status !== '运行中') ? '需关注' : '正常'} trend="实时巡检" />
      </div>
      <Panel title={`${title} 列表`} action="批量操作" icon={Layers3}>
        {showBlockingLoading && <StateMessage title="正在读取节点资源" desc={`正在通过 daemon 查询 ${activeNode?.name || '当前节点'}。`} />}
        {!showBlockingLoading && error && (
          <StateMessage
            title="节点暂时不可用"
            desc={friendlyError(error)}
            tone="danger"
            actions={[
              { label: '重试', onClick: onRetry },
              { label: '检查节点配置' },
            ]}
          />
        )}
        {!showBlockingLoading && !error && scoped.length === 0 && <StateMessage title="暂无资源" desc="当前节点没有返回该类型资源，或搜索条件没有匹配项。" actions={[{ label: isContainerView ? '创建容器' : '创建资源', onClick: isContainerView ? () => setCreatingContainer(true) : undefined }]} />}
        {!showBlockingLoading && !error && scoped.length > 0 && (
          isContainerView
            ? <ContainerCardGrid containers={scoped} onOpen={openContainer} />
            : <ResourceTable resources={scoped} />
        )}
      </Panel>
      {creatingContainer && (
        <ContainerCreateWizard
          node={activeNode}
          notify={notify}
          onClose={() => setCreatingContainer(false)}
          onCreated={() => {
            setCreatingContainer(false);
            onRetry?.();
          }}
        />
      )}
    </section>
  );
}

function friendlyError(error) {
  if (error === 'Failed to fetch') {
    return '无法连接北冥后端服务，请确认 API 服务已启动，或稍后重试。';
  }
  if (error.includes('ENOENT')) {
    return 'Daemon 配置不可用，请检查节点配置。';
  }
  if (error.toLowerCase().includes('timed out')) {
    return 'Daemon 连接超时，请检查节点地址、端口和防火墙。';
  }
  return error;
}

function readRouteState() {
  const params = new URLSearchParams(window.location.search);
  const view = params.get('view') || 'overview';
  return {
    view: ['overview', 'vm', 'containers', 'nodes', 'network', 'identity', 'security'].includes(view) ? view : 'overview',
    nodeId: params.get('node') || '',
    containerId: params.get('container') || '',
  };
}

function updateRouteState({ view, nodeId, containerId }) {
  const params = new URLSearchParams();
  if (view && view !== 'overview') params.set('view', view);
  if (nodeId) params.set('node', nodeId);
  if (containerId) params.set('container', containerId);
  const queryString = params.toString();
  const nextUrl = `${window.location.pathname}${queryString ? `?${queryString}` : ''}`;
  window.history.replaceState(null, '', nextUrl);
}

function readCachedContainers(nodeId) {
  if (!nodeId || typeof window === 'undefined') return [];
  try {
    const raw = window.localStorage.getItem(`${CONTAINER_CACHE_PREFIX}${nodeId}`);
    if (!raw) return [];
    const payload = JSON.parse(raw);
    if (!payload?.savedAt || Date.now() - payload.savedAt > CONTAINER_CACHE_TTL) return [];
    return Array.isArray(payload.items) ? payload.items : [];
  } catch {
    return [];
  }
}

function writeCachedContainers(nodeId, items) {
  if (!nodeId || !Array.isArray(items) || typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(`${CONTAINER_CACHE_PREFIX}${nodeId}`, JSON.stringify({
      savedAt: Date.now(),
      items,
    }));
    for (const item of items) {
      writeCachedContainer(nodeId, item);
    }
  } catch {
    // Cache is only for faster first paint.
  }
}

function readCachedContainer(nodeId, containerId) {
  if (!nodeId || !containerId || typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage.getItem(`${CONTAINER_DETAIL_CACHE_PREFIX}${nodeId}:${containerId}`);
    if (!raw) return null;
    const payload = JSON.parse(raw);
    if (!payload?.savedAt || Date.now() - payload.savedAt > CONTAINER_CACHE_TTL) return null;
    return payload.item || null;
  } catch {
    return null;
  }
}

function writeCachedContainer(nodeId, item) {
  if (!nodeId || !item || typeof window === 'undefined') return;
  const keys = [item.id, item.name].filter(Boolean);
  if (item.ID) keys.push(item.ID);
  if (item.Names) keys.push(item.Names);
  try {
    const payload = JSON.stringify({ savedAt: Date.now(), item });
    for (const key of new Set(keys)) {
      window.localStorage.setItem(`${CONTAINER_DETAIL_CACHE_PREFIX}${nodeId}:${key}`, payload);
    }
  } catch {
    // Cache is only for faster first paint.
  }
}

function StateMessage({ title, desc, tone = 'default', actions = [] }) {
  const Icon = tone === 'danger' ? AlertCircle : Cloud;
  return (
    <div className={`state-message ${tone}`}>
      <div className="state-illustration">
        <Icon size={28} />
      </div>
      <strong>{title}</strong>
      <span>{desc}</span>
      {actions.length > 0 && (
        <div className="state-actions">
          {actions.map((action, index) => (
            <button className={index === 0 ? 'state-primary' : 'state-secondary'} key={action.label} onClick={action.onClick} type="button">
              {action.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function ContainerCardGrid({ containers, onOpen }) {
  return (
    <div className="container-grid">
      {containers.map((container) => (
        <button className="container-card" key={container.id} onClick={() => onOpen(container)} type="button">
          <div className="container-card-head">
            <DockerImageIcon image={container.image} />
            <div>
              <strong>{container.name}</strong>
              <span>{container.image}</span>
            </div>
            <ContainerState status={container.status} />
          </div>
          <div className="container-stats">
            <ContainerMetric label="CPU" value={`${Number(container.stats.cpuPercent || 0).toFixed(2)}%`} percent={container.stats.cpuPercent || 0} />
            <ContainerMetric label="内存" value={`${formatBytes(container.stats.memoryUsedBytes || 0)} / ${formatBytes(container.stats.memoryLimitBytes || 0)}`} percent={container.stats.memoryPercent || 0} />
            <ContainerMetric label="Swap" value={formatBytes(container.stats.swapUsedBytes || 0)} />
            <ContainerMetric label="网络" value={`↓ ${formatRate(container.stats.networkDownloadBps || 0)} / ↑ ${formatRate(container.stats.networkUploadBps || 0)}`} />
          </div>
          <div className="container-meta">
            <InfoChip label="网络模式" value={container.network?.mode || '-'} />
            <PortMappingChip ports={container.network?.ports} />
          </div>
        </button>
      ))}
    </div>
  );
}

function DockerImageIcon({ image }) {
  const [failed, setFailed] = useState(false);
  const url = getDockerHubLogoUrl(image);
  if (!url || failed) {
    return <div className="docker-fallback"><Container size={20} /></div>;
  }
  return <img className="docker-logo" src={url} alt="" onError={() => setFailed(true)} />;
}

function ContainerState({ status }) {
  const running = status === '运行中';
  const pending = status === '部署中' || status === '待启动';
  return (
    <span className={running ? 'container-state running' : pending ? 'container-state pending' : 'container-state stopped'}>
      <StatusIcon tone={running ? 'ok' : pending ? 'pending' : 'danger'} />
      {status}
    </span>
  );
}

function getNodeDaemonUrl(node) {
  if (node.daemonUrl) return node.daemonUrl;
  if (node.host) return `http://${node.host}:8790`;
  return 'http://127.0.0.1:8790';
}

function StatusIcon({ tone }) {
  if (tone === 'ok') {
    return <span className="status-icon ok"><Check size={9} strokeWidth={3} /></span>;
  }
  if (tone === 'danger') {
    return <span className="status-icon danger"><X size={9} strokeWidth={3} /></span>;
  }
  if (tone === 'pending') {
    return <span className="status-icon pending"><RotateCw size={9} strokeWidth={2.8} /></span>;
  }
  return <span className="status-icon muted"><Minus size={8} strokeWidth={3} /></span>;
}

function ContainerMetric({ label, value, percent }) {
  return (
    <div className="container-metric">
      <span>{label}</span>
      <strong>{value}</strong>
      {percent !== undefined && <i><b style={{ width: `${Math.min(Number(percent) || 0, 100)}%` }}></b></i>}
    </div>
  );
}

function InfoChip({ label, value }) {
  return (
    <div className="info-chip">
      <span>{label}</span>
      <strong title={value}>{value}</strong>
    </div>
  );
}

function ContainerRouteRestoring({ activeNode, containerId, loading, error, onBack, onRetry }) {
  return (
    <section className="container-detail route-restoring">
      <button className="back-link" onClick={onBack} type="button"><ArrowLeft size={17} />返回容器列表</button>
      <div className="container-detail-head skeleton">
        <div className="console-title">
          <div className="docker-fallback loading"><Container size={26} /></div>
          <div>
            <h1>正在恢复容器控制台</h1>
            <div className="console-badges">
              <span>{activeNode?.name || 'Daemon'}</span>
              <span>{containerId}</span>
            </div>
          </div>
        </div>
      </div>
      <StateMessage
        actions={error ? [
          { label: '重试', onClick: onRetry },
          { label: '返回列表', onClick: onBack },
        ] : []}
        desc={error ? friendlyError(error) : `正在通过 daemon 查询 ${activeNode?.name || '当前节点'} 的容器状态。`}
        title={error ? '容器控制台恢复失败' : loading ? '正在读取容器信息' : '正在匹配容器'}
        tone={error ? 'danger' : 'default'}
      />
    </section>
  );
}

function ReviewRow({ label, value }) {
  return (
    <div className="review-row">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function ReviewCard({ title, value, detail }) {
  return (
    <article className="review-card">
      <div>
        <span>{title}</span>
        <strong>{value}</strong>
      </div>
      <p title={detail}>{detail}</p>
    </article>
  );
}

const CONTAINER_FINAL_STATES = new Set(['running', 'exited', 'created', 'dead']);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function sameContainerIdentity(item, identity = {}) {
  if (!item) return false;
  return Boolean(
    identity.id && item.id === identity.id
      || identity.name && item.name === identity.name
      || identity.fallbackName && item.name === identity.fallbackName
  );
}

async function waitForContainerState(nodeId, identity, predicate, options = {}) {
  const timeout = options.timeout || 20000;
  const interval = options.interval || 260;
  const startedAt = Date.now();
  let lastMatch = null;
  while (Date.now() - startedAt < timeout) {
    let match = null;
    let items = null;
    if (identity.id) {
      try {
        match = await fetchContainer(nodeId, identity.id, { fast: true });
      } catch (error) {
        if (!/not found|404|container not found/i.test(error.message || '')) throw error;
      }
    }
    if (!match && identity.name) {
      const list = await fetchContainers(nodeId, { fast: true });
      items = list;
      match = list.find((item) => sameContainerIdentity(item, identity));
    }
    if (match) lastMatch = match;
    if (predicate(match, items || (match ? [match] : []))) return match || null;
    await sleep(interval);
  }
  const message = options.timeoutMessage || '操作已提交，但状态确认超时';
  throw new Error(message);
}

function ContainerDetailPage({ container, node, notify, onBack, onRefresh, onSaved, onContainerUpdate }) {
  const [busyAction, setBusyAction] = useState('');
  const busyActionRef = useRef('');
  const [editing, setEditing] = useState(false);
  const [editSnapshot, setEditSnapshot] = useState(null);
  const [openingEdit, setOpeningEdit] = useState(false);
  const running = container.status === '运行中';
  const canStart = !running;
  useEffect(() => {
    preloadTerminalModules();
  }, []);
  useEffect(() => {
    busyActionRef.current = busyAction;
  }, [busyAction]);
  const runOperation = async (operation, label) => {
    const currentBusyAction = busyActionRef.current;
    const canInterruptStop = operation === 'kill' && currentBusyAction === 'stop';
    if (currentBusyAction && !canInterruptStop) return;
    setBusyAction(operation);
    busyActionRef.current = operation;
    try {
      const previousStartedAt = container.raw?.startedAt || container.startedAt || '';
      await operateContainer(node.id, container.id, operation);
      const latest = await waitForContainerState(
        node.id,
        { id: container.id, name: container.name },
        (item) => {
          const state = item?.state || '';
          if (operation === 'start') return state === 'running';
          if (operation === 'stop' || operation === 'kill') return ['exited', 'created', 'dead'].includes(state);
          if (operation === 'restart') {
            return state === 'running' && (!previousStartedAt || item.startedAt !== previousStartedAt);
          }
          return item && CONTAINER_FINAL_STATES.has(state);
        },
        { timeoutMessage: `${label}已提交，但状态确认超时` },
      );
      if (busyActionRef.current !== operation) return;
      const synced = onContainerUpdate?.(latest);
      notify?.({ title: `${label}完成`, message: synced?.name || latest?.name || container.name });
      setTimeout(() => onRefresh?.(), 80);
    } catch (error) {
      if (busyActionRef.current !== operation) return;
      notify?.({ type: 'error', title: `${label}失败`, message: friendlyError(error.message), duration: 4600 });
    } finally {
      if (busyActionRef.current === operation) {
        busyActionRef.current = '';
        setBusyAction('');
      }
    }
  };
  const actionLocked = Boolean(busyAction);
  const killEnabled = running && (!actionLocked || busyAction === 'stop');
  const openEditor = async () => {
    if (actionLocked || openingEdit) return;
    setOpeningEdit(true);
    let nextSnapshot = container;
    try {
      const latest = await fetchContainer(node.id, container.id, { fast: true });
      nextSnapshot = onContainerUpdate?.(latest) || container;
    } catch {
      // Use the current snapshot if a transient refresh fails.
    } finally {
      setOpeningEdit(false);
      setEditSnapshot(nextSnapshot);
      setEditing(true);
    }
  };

  return (
    <section className="container-detail">
      <button className="back-link" onClick={onBack} type="button"><ArrowLeft size={17} />返回容器列表</button>
      <div className="container-detail-head">
        <div className="console-title">
          <DockerImageIcon image={container.image} />
          <div>
            <h1>{container.name}</h1>
            <div className="console-badges">
              <ContainerState status={container.status} />
              <span>{container.image}</span>
            </div>
          </div>
        </div>
        <div className="container-console-actions">
          <button className="console-action" disabled={!canStart || actionLocked} onClick={() => runOperation('start', '开启')} type="button"><Power size={17} />{busyAction === 'start' ? '开启中...' : '开启'}</button>
          <button className="console-action" disabled={!running || actionLocked} onClick={() => runOperation('restart', '重启')} type="button"><RotateCw size={17} />{busyAction === 'restart' ? '重启中...' : '重启'}</button>
          <button className="console-action danger" disabled={!running || actionLocked} onClick={() => runOperation('stop', '关闭')} type="button"><Square size={16} />{busyAction === 'stop' ? '关闭中...' : '关闭'}</button>
          <button className="console-action danger outline" disabled={!killEnabled} onClick={() => runOperation('kill', '终止')} type="button"><X size={18} />{busyAction === 'kill' ? '终止中...' : busyAction === 'stop' ? '强制终止' : '终止'}</button>
          <button className="console-action" disabled={actionLocked || openingEdit} onClick={openEditor} type="button"><Settings2 size={17} />{openingEdit ? '读取中...' : '编辑'}</button>
        </div>
      </div>

      <ContainerTerminal container={container} node={node} title={container.name} />
      <ContainerFileManager container={container} node={node} notify={notify} />
      {editing && (
        <ContainerEditModal
          container={editSnapshot || container}
          node={node}
          notify={notify}
          onClose={() => {
            setEditing(false);
            setEditSnapshot(null);
          }}
          onDeleted={() => {
            setEditing(false);
            setEditSnapshot(null);
            onBack?.();
            onRefresh?.();
          }}
          onSaved={async (savedName, latest) => {
            setEditing(false);
            setEditSnapshot(null);
            await onSaved?.(savedName, latest);
            onRefresh?.();
          }}
        />
      )}
    </section>
  );
}

function ContainerFileManager({ container, node, notify }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <section className="container-file-entry">
        <div>
          <Folder size={18} />
          <div>
            <strong>工作目录文件</strong>
            <span>{container.config?.workingDir || '/'} · 新建、上传、下载、重命名</span>
          </div>
        </div>
        <button onClick={() => setOpen(true)} type="button"><Folder size={16} />打开文件</button>
      </section>
      {open && <ContainerFileModal container={container} node={node} notify={notify} onClose={() => setOpen(false)} />}
    </>
  );
}

const FILE_COLUMNS = [
  { key: 'name', label: '名称' },
  { key: 'modified', label: '修改时间' },
  { key: 'type', label: '类型' },
  { key: 'size', label: '大小' },
];

const FILE_COLUMN_DEFAULT_WIDTHS = {
  name: 520,
  modified: 210,
  type: 160,
  size: 120,
};

const FILE_COLUMN_MIN_WIDTHS = {
  name: 240,
  modified: 150,
  type: 110,
  size: 90,
};

const FILE_COLUMN_STORAGE_KEY = 'beiming-file-column-widths';

function readFileColumnWidths() {
  if (typeof window === 'undefined') return FILE_COLUMN_DEFAULT_WIDTHS;
  try {
    const stored = JSON.parse(window.localStorage.getItem(FILE_COLUMN_STORAGE_KEY) || '{}');
    return FILE_COLUMNS.reduce((widths, column) => {
      const fallback = FILE_COLUMN_DEFAULT_WIDTHS[column.key];
      const width = Number(stored[column.key]);
      widths[column.key] = Number.isFinite(width)
        ? Math.max(FILE_COLUMN_MIN_WIDTHS[column.key], Math.min(1200, Math.round(width)))
        : fallback;
      return widths;
    }, {});
  } catch {
    return FILE_COLUMN_DEFAULT_WIDTHS;
  }
}

function ContainerFileModal({ container, node, notify, onClose }) {
  useBodyScrollLock();
  const initialPath = normalizeContainerPath(container.config?.workingDir || '/');
  const [path, setPath] = useState(initialPath);
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [dragItem, setDragItem] = useState(null);
  const [dropTarget, setDropTarget] = useState('');
  const [dropHint, setDropHint] = useState(null);
  const [externalDragging, setExternalDragging] = useState(false);
  const [selectedNames, setSelectedNames] = useState([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [contextMenu, setContextMenu] = useState(null);
  const [renamingName, setRenamingName] = useState('');
  const [renameValue, setRenameValue] = useState('');
  const [pendingCreate, setPendingCreate] = useState(null);
  const [deleteDialog, setDeleteDialog] = useState(null);
  const [extractDialog, setExtractDialog] = useState(null);
  const [uploadConfirm, setUploadConfirm] = useState(null);
  const [editorState, setEditorState] = useState(null);
  const [fileSort, setFileSort] = useState({ key: 'name', direction: 'asc' });
  const [fileClipboard, setFileClipboard] = useState(null);
  const [selectionBox, setSelectionBox] = useState(null);
  const [uploadProgress, setUploadProgress] = useState(null);
  const [downloadProgress, setDownloadProgress] = useState(null);
  const [offlineWritable, setOfflineWritable] = useState(false);
  const [pathHistory, setPathHistory] = useState({ back: [], forward: [] });
  const [fileColumnWidths, setFileColumnWidths] = useState(readFileColumnWidths);
  const uploadRef = useRef(null);
  const uploadAbortRef = useRef(null);
  const downloadAbortRef = useRef(null);
  const downloadIdRef = useRef('');
  const uploadQueueRef = useRef(Promise.resolve());
  const activeUploadIdsRef = useRef(new Set());
  const pathRef = useRef(path);
  const filesTableRef = useRef(null);
  const breadcrumbRef = useRef(null);
  const monacoEditorRef = useRef(null);
  const selectionBaseRef = useRef([]);
  const renameSubmittingRef = useRef(false);
  const dropHintTimerRef = useRef(null);
  const dropHintPendingPathRef = useRef('');
  const dropHintLatestRef = useRef(null);
  const renameClickTimerRef = useRef(null);
  const running = container.status === '运行中';
  const canWriteFiles = running || offlineWritable;
  const readOnly = !canWriteFiles;
  const fileColumnTemplate = FILE_COLUMNS.map((column) => `${fileColumnWidths[column.key]}px`).join(' ');
  const fileColumnStyle = useMemo(() => ({ gridTemplateColumns: fileColumnTemplate }), [fileColumnTemplate]);
  const visibleItems = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase();
    const filtered = items.filter((item) => item.name.toLowerCase().includes(keyword));
    const direction = fileSort.direction === 'desc' ? -1 : 1;
    const getValue = (item) => {
      if (fileSort.key === 'modified') return Number(item.modified || 0);
      if (fileSort.key === 'location') return path === '/' ? '/' : path;
      if (fileSort.key === 'type') return item.type === 'd' ? '文件夹' : '文件';
      if (fileSort.key === 'size') return item.type === 'd' ? -1 : Number(item.size || 0);
      return String(item.name || '').toLowerCase();
    };
    return [...filtered].sort((left, right) => {
      const leftIsDir = left.type === 'd';
      const rightIsDir = right.type === 'd';
      if (leftIsDir !== rightIsDir) return leftIsDir ? -1 : 1;
      const leftValue = getValue(left);
      const rightValue = getValue(right);
      if (typeof leftValue === 'number' && typeof rightValue === 'number') return (leftValue - rightValue) * direction;
      return String(leftValue).localeCompare(String(rightValue), 'zh-Hans-CN', { numeric: true }) * direction;
    });
  }, [fileSort, items, path, searchQuery]);
  const selectedItems = items.filter((item) => selectedNames.includes(item.name));
  const archiveSelected = selectedItems.some((item) => item.type !== 'd' && isArchiveFile(item.name));

  const navigateToPath = (nextPath, options = {}) => {
    const normalized = normalizeContainerPath(nextPath);
    if (normalized === path) return;
    setContextMenu(null);
    if (!options.replace) {
      setPathHistory((current) => ({
        back: [...current.back, path],
        forward: [],
      }));
    }
    setPath(normalized);
  };

  const loadFiles = async (nextPath = path) => {
    if (filesTableRef.current) filesTableRef.current.scrollTop = 0;
    setLoading(true);
    setError('');
    try {
      const result = await fetchContainerFiles(node.id, container.id, nextPath);
      setItems(result.items || []);
      setPath(normalizeContainerPath(result.path || nextPath));
      setOfflineWritable(Boolean(result.writable));
      setSelectedNames([]);
    } catch (loadError) {
      setOfflineWritable(false);
      setError(friendlyError(loadError.message));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setPath(initialPath);
    setOfflineWritable(false);
    setPathHistory({ back: [], forward: [] });
  }, [container.id, initialPath]);

  useEffect(() => {
    pathRef.current = path;
  }, [path]);

  useEffect(() => {
    const cleanupBeforeUnload = () => {
      const uploadIds = Array.from(activeUploadIdsRef.current);
      if (uploadIds.length) beaconCleanupContainerUploads(node.id, container.id, uploadIds);
    };
    window.addEventListener('beforeunload', cleanupBeforeUnload);
    return () => {
      window.removeEventListener('beforeunload', cleanupBeforeUnload);
      cleanupBeforeUnload();
    };
  }, [container.id, node.id]);

  useEffect(() => {
    loadFiles(path);
  }, [container.id, node.id, running, path]);

  useEffect(() => () => {
    if (renameClickTimerRef.current) clearTimeout(renameClickTimerRef.current);
  }, []);

  useEffect(() => {
    if (!selectionBox?.active) return undefined;
    const moveSelectionBox = (event) => {
      event.preventDefault();
      const nextBox = {
        ...selectionBox,
        x: event.clientX,
        y: event.clientY,
      };
      setSelectionBox(nextBox);
      const rect = normalizeRect(nextBox.startX, nextBox.startY, nextBox.x, nextBox.y);
      const names = Array.from(filesTableRef.current?.querySelectorAll('.file-entry[data-file-name]') || [])
        .filter((row) => rectsIntersect(rect, row.getBoundingClientRect()))
        .map((row) => row.getAttribute('data-file-name'))
        .filter(Boolean);
      const merged = event.ctrlKey || event.metaKey ? [...selectionBaseRef.current, ...names] : names;
      setSelectedNames(Array.from(new Set(merged)));
    };
    const endSelectionBox = () => {
      setSelectionBox(null);
    };
    window.addEventListener('mousemove', moveSelectionBox);
    window.addEventListener('mouseup', endSelectionBox, { once: true });
    return () => {
      window.removeEventListener('mousemove', moveSelectionBox);
      window.removeEventListener('mouseup', endSelectionBox);
    };
  }, [selectionBox]);

  const runFileAction = async (label, action) => {
    setBusy(label);
    try {
      await action();
      await loadFiles(path);
      notify?.({ title: `${label}成功`, message: path });
    } catch (actionError) {
      notify?.({ type: 'error', title: `${label}失败`, message: friendlyError(actionError.message), duration: 4600 });
    } finally {
      setBusy('');
    }
  };

  const createEntry = (action) => {
    if (!canWriteFiles || busy || pendingCreate) return;
    const isDir = action === 'mkdir';
    const name = nextNewEntryName(isDir ? '新建文件夹' : '新建文件', items.map((item) => item.name));
    setSelectedNames([]);
    setPendingCreate({ action, name, type: isDir ? 'd' : 'f' });
  };

  const uploadFile = async (event) => {
    const files = Array.from(event.target.files || []);
    event.target.value = '';
    if (!files.length) return;
    await uploadFiles(files, path);
  };

  const uploadFiles = async (files, targetPath = path) => {
    const uploadItems = Array.from(files || []);
    if (!uploadItems.length) return Promise.resolve();
    const normalizedTargetPath = normalizeContainerPath(targetPath);
    try {
      const existingItems = normalizedTargetPath === normalizeContainerPath(path)
        ? items
        : (await fetchContainerFiles(node.id, container.id, normalizedTargetPath)).items || [];
      const existingNames = new Set(existingItems.map((item) => item.name));
      const conflicts = uploadItems.filter((file) => existingNames.has(file.name));
      if (conflicts.length) {
        setUploadConfirm({
          files: uploadItems,
          targetPath: normalizedTargetPath,
          conflicts: conflicts.map((file) => ({ name: file.name, size: file.size })),
        });
        return Promise.resolve();
      }
    } catch (checkError) {
      notify?.({ type: 'error', title: '上传检查失败', message: friendlyError(checkError.message), duration: 4600 });
      return Promise.resolve();
    }
    return enqueueUploadFiles(uploadItems, normalizedTargetPath);
  };

  const enqueueUploadFiles = (uploadItems, targetPath) => {
    if (uploadAbortRef.current) {
      notify?.({ title: '已加入上传队列', message: uploadItems.length > 1 ? `${uploadItems.length} 个文件` : uploadItems[0].name });
    }
    const task = uploadQueueRef.current
      .catch(() => undefined)
      .then(() => runUploadBatch(uploadItems, targetPath));
    uploadQueueRef.current = task.catch(() => undefined);
    return task;
  };

  const confirmOverwriteUpload = () => {
    if (!uploadConfirm?.files?.length) return;
    const { files, targetPath } = uploadConfirm;
    setUploadConfirm(null);
    enqueueUploadFiles(files, targetPath);
  };

  const runUploadBatch = async (files, targetPath) => {
    const controller = new AbortController();
    uploadAbortRef.current = controller;
    const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
    let uploadedBytes = 0;
    const startedAt = performance.now();
    const batchUploadIds = new Set();
    const cleanupBatchUploads = async () => {
      const uploadIds = Array.from(batchUploadIds);
      if (!uploadIds.length) return;
      uploadIds.forEach((uploadId) => activeUploadIdsRef.current.delete(uploadId));
      try {
        await cleanupContainerUploads(node.id, container.id, uploadIds);
      } catch {
        beaconCleanupContainerUploads(node.id, container.id, uploadIds);
      }
    };
    try {
      setUploadProgress({ current: files[0]?.name || '文件', index: 0, total: files.length, percent: 0, speed: '0 B/s' });
      const fileWorkerCount = Math.min(totalBytes > 512 * 1024 * 1024 ? 2 : 4, files.length);
      const chunkWorkerCap = Math.max(2, Math.floor(16 / fileWorkerCount));
      const workers = Array.from({ length: fileWorkerCount }, async (_, workerIndex) => {
        for (let index = workerIndex; index < files.length; index += fileWorkerCount) {
          if (controller.signal.aborted) return;
          const file = files[index];
          setUploadProgress((current) => ({ ...(current || {}), current: file.name, index: index + 1, total: files.length }));
          await uploadFileInChunks(file, targetPath, controller.signal, (uploadId) => {
            batchUploadIds.add(uploadId);
            activeUploadIdsRef.current.add(uploadId);
          }, (delta) => {
            uploadedBytes += delta;
            const percent = totalBytes ? Math.min(99, Math.round((uploadedBytes / totalBytes) * 100)) : 100;
            const elapsedSeconds = Math.max(0.3, (performance.now() - startedAt) / 1000);
            setUploadProgress((current) => ({ ...(current || {}), percent, speed: `${formatBytes(uploadedBytes / elapsedSeconds)}/s` }));
          }, chunkWorkerCap);
        }
      });
      await Promise.all(workers);
      setUploadProgress((current) => ({ ...(current || {}), percent: 100 }));
      await new Promise((resolve) => setTimeout(resolve, 180));
      if (pathRef.current === targetPath) await loadFiles(targetPath);
      notify?.({ title: '上传成功', message: files.length > 1 ? `${files.length} 个文件` : files[0].name });
    } catch (uploadError) {
      await cleanupBatchUploads();
      if (uploadError.name === 'AbortError') {
        notify?.({ title: '已取消上传', message: files.length > 1 ? `${files.length} 个文件` : files[0].name });
      } else {
        notify?.({ type: 'error', title: '上传失败', message: friendlyError(uploadError.message), duration: 4600 });
      }
    } finally {
      batchUploadIds.forEach((uploadId) => activeUploadIdsRef.current.delete(uploadId));
      if (uploadAbortRef.current === controller) uploadAbortRef.current = null;
      setUploadProgress(null);
    }
  };

  const cancelUpload = () => {
    uploadAbortRef.current?.abort();
  };

  const uploadFileInChunks = async (file, targetPath, signal, onUploadId, onProgress, workerCap = 8) => {
    const { chunkSize, workerCount } = getUploadPlan(file.size, workerCap);
    const uploadId = `${Date.now()}-${Math.random().toString(16).slice(2)}-${file.name}`;
    onUploadId(uploadId);
    const totalChunks = Math.max(1, Math.ceil(file.size / chunkSize));
    let nextChunk = 0;
    const uploadChunkWithRetry = async (payload) => {
      let lastError;
      for (let attempt = 0; attempt < 3; attempt += 1) {
        if (signal.aborted) throw new DOMException('Upload cancelled', 'AbortError');
        try {
          return await uploadContainerFileChunkBinary(node.id, container.id, payload);
        } catch (error) {
          lastError = error;
          if (attempt === 2 || signal.aborted) break;
          await new Promise((resolve) => setTimeout(resolve, 260 * (attempt + 1)));
        }
      }
      throw lastError;
    };
    const uploadChunk = async () => {
      while (nextChunk < totalChunks) {
        if (signal.aborted) throw new DOMException('Upload cancelled', 'AbortError');
        const chunkIndex = nextChunk;
        nextChunk += 1;
        const start = chunkIndex * chunkSize;
        const end = Math.min(file.size, start + chunkSize);
        const chunk = file.slice(start, end);
        await uploadChunkWithRetry({
          uploadId,
          path: targetPath,
          name: file.name,
          chunkIndex,
          totalChunks,
          chunkSize,
          chunk,
          size: file.size,
          signal,
        });
        onProgress(end - start);
      }
    };
    await Promise.all(Array.from({ length: workerCount }, uploadChunk));
  };

  const moveItem = async (item, targetPath) => {
    const source = joinContainerPath(path, item.name);
    if (source === targetPath || path === targetPath) return;
    await runFileAction('移动', () => renameContainerFile(node.id, container.id, source, item.name, targetPath));
  };

  const handleNativeDrop = async (event, targetPath = path) => {
    event.preventDefault();
    event.stopPropagation();
    clearDropHint();
    setExternalDragging(false);
    setDropTarget('');
    if (!canWriteFiles) return;
    const files = Array.from(event.dataTransfer.files || []);
    if (files.length > 0) {
      await uploadFiles(files, targetPath);
      return;
    }
    const payload = event.dataTransfer.getData('application/x-beiming-file');
    if (!payload) return;
    const item = JSON.parse(payload);
    if (item.path === targetPath || joinContainerPath(targetPath, item.name) === item.path) return;
    await runFileAction('移动', () => renameContainerFile(node.id, container.id, item.path, item.name, targetPath));
  };

  const handleDragOver = (event, targetPath = path) => {
    event.preventDefault();
    event.stopPropagation();
    if (!canWriteFiles) return;
    event.dataTransfer.dropEffect = event.dataTransfer.types.includes('Files') ? 'copy' : 'move';
    setDropTarget(targetPath);
    if (event.dataTransfer.types.includes('Files')) setExternalDragging(true);
  };

  const handleFilePanelDragOver = (event) => {
    if (!canWriteFiles && event.dataTransfer.types.includes('Files')) {
      clearDropHint();
      setExternalDragging(false);
      setDropTarget('');
      return;
    }
    if (event.dataTransfer.types.includes('Files')) {
      event.preventDefault();
      event.stopPropagation();
      event.dataTransfer.dropEffect = 'copy';
      setExternalDragging(true);
      setDropTarget('');
      clearDropHint();
      return;
    }
    event.preventDefault();
    const target = event.target instanceof Element ? event.target : null;
    if (!target?.closest('.file-entry[data-file-kind="dir"]')) {
      setDropTarget('');
      clearDropHint();
    }
  };

  const clearDropHint = () => {
    if (dropHintTimerRef.current) {
      clearTimeout(dropHintTimerRef.current);
      dropHintTimerRef.current = null;
    }
    dropHintPendingPathRef.current = '';
    dropHintLatestRef.current = null;
    setDropHint(null);
  };

  const handleFolderDragOver = (event, targetPath, itemName, invalid) => {
    handleDragOver(event, targetPath);
    const x = event.clientX + 14;
    const y = event.clientY + 14;
    if (dropHint?.path === targetPath) {
      setDropHint((current) => current ? { ...current, x, y } : current);
      return;
    }
    dropHintLatestRef.current = {
      invalid,
      path: targetPath,
      text: invalid
        ? '不能将文件夹移动到其自身，请重新选择'
        : event.dataTransfer.types.includes('Files')
          ? `松开鼠标，即可上传到 “${itemName}”`
          : `松开鼠标，即可移动 1 项到 “${itemName}”`,
      x,
      y,
    };
    if (dropHintPendingPathRef.current === targetPath) return;
    if (dropHintTimerRef.current) clearTimeout(dropHintTimerRef.current);
    dropHintPendingPathRef.current = targetPath;
    dropHintTimerRef.current = setTimeout(() => {
      setDropHint(dropHintLatestRef.current);
      dropHintTimerRef.current = null;
    }, 100);
  };

  const clearInternalFileDropTarget = (event) => {
    event.preventDefault();
    event.stopPropagation();
    if (event.dataTransfer.types.includes('Files')) setExternalDragging(true);
    clearDropHint();
    setDropTarget('');
  };

  const startSelectionBox = (event) => {
    if (event.button !== 0 || dragItem || externalDragging || renamingName) return;
    const target = event.target instanceof Element ? event.target : null;
    if (!target || target.closest('button, input, textarea, .files-head, .files-toolbar, .files-row-head, .files-drop-hint, .files-statusbar, .files-context-menu, .file-upload-progress, .file-drop-tooltip')) return;
    event.preventDefault();
    closeContextMenu();
    cancelScheduledRename();
    selectionBaseRef.current = event.ctrlKey || event.metaKey ? selectedNames : [];
    if (!event.ctrlKey && !event.metaKey) setSelectedNames([]);
    setSelectionBox({
      active: true,
      startX: event.clientX,
      startY: event.clientY,
      x: event.clientX,
      y: event.clientY,
    });
  };

  const leaveDropTarget = (event, targetPath) => {
    setDropTarget((current) => (current === targetPath ? '' : current));
    if (dropHint?.path === targetPath) clearDropHint();
  };

  const startItemDrag = (event, item) => {
    clearDropHint();
    const payload = { name: item.name, path: joinContainerPath(path, item.name), type: item.type };
    setDragItem(payload);
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('application/x-beiming-file', JSON.stringify(payload));
    event.dataTransfer.setData('text/plain', item.name);
    const dragGhost = document.createElement('canvas');
    dragGhost.width = 1;
    dragGhost.height = 1;
    event.dataTransfer.setDragImage(dragGhost, 0, 0);
  };

  const stopDragging = () => {
    clearDropHint();
    setDragItem(null);
    setDropTarget('');
    setExternalDragging(false);
  };

  const closeContextMenu = () => setContextMenu(null);

  const changeFileSort = (key) => {
    setFileSort((current) => ({
      key,
      direction: current.key === key && current.direction === 'asc' ? 'desc' : 'asc',
    }));
  };

  const startFileColumnResize = (event, key) => {
    event.preventDefault();
    event.stopPropagation();
    const startX = event.clientX;
    const startWidth = fileColumnWidths[key];
    const previousCursor = document.body.style.cursor;
    const previousUserSelect = document.body.style.userSelect;
    let latestWidths = fileColumnWidths;
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';

    const handleMove = (moveEvent) => {
      const nextWidth = Math.max(FILE_COLUMN_MIN_WIDTHS[key], Math.round(startWidth + moveEvent.clientX - startX));
      setFileColumnWidths((current) => {
        latestWidths = { ...current, [key]: nextWidth };
        return latestWidths;
      });
    };

    const stopResize = () => {
      window.removeEventListener('mousemove', handleMove);
      window.removeEventListener('mouseup', stopResize);
      document.body.style.cursor = previousCursor;
      document.body.style.userSelect = previousUserSelect;
      try {
        window.localStorage.setItem(FILE_COLUMN_STORAGE_KEY, JSON.stringify(latestWidths));
      } catch {
        // Ignore private-mode storage failures; the resize still works for this session.
      }
    };

    window.addEventListener('mousemove', handleMove);
    window.addEventListener('mouseup', stopResize);
  };

  const openContextMenu = (event, item) => {
    event.preventDefault();
    event.stopPropagation();
    setSelectedNames((current) => current.includes(item.name) ? current : [item.name]);
    setContextMenu({
      item,
      x: Math.min(event.clientX, window.innerWidth - 190),
      y: Math.min(event.clientY, window.innerHeight - 190),
    });
  };

  const openBlankContextMenu = (event) => {
    const target = event.target instanceof Element ? event.target : null;
    if (!target || target.closest('.file-entry, .files-row-head, button, input, textarea, .files-drop-hint')) return;
    event.preventDefault();
    event.stopPropagation();
    closeContextMenu();
    setSelectedNames([]);
    setContextMenu({
      item: null,
      x: Math.min(event.clientX, window.innerWidth - 190),
      y: Math.min(event.clientY, window.innerHeight - 230),
    });
  };

  const startRenameItem = (item) => {
    if (readOnly) return;
    if (renameClickTimerRef.current) {
      clearTimeout(renameClickTimerRef.current);
      renameClickTimerRef.current = null;
    }
    renameSubmittingRef.current = false;
    setRenamingName(item.name);
    setRenameValue(item.name);
  };

  const scheduleRenameFromNameClick = (event, item, selected) => {
    if (readOnly) return;
    if (renamingName || event.ctrlKey || event.metaKey || event.shiftKey) return;
    if (!selected) return;
    event.stopPropagation();
    if (renameClickTimerRef.current) clearTimeout(renameClickTimerRef.current);
    renameClickTimerRef.current = setTimeout(() => {
      startRenameItem(item);
      renameClickTimerRef.current = null;
    }, 520);
  };

  const cancelScheduledRename = () => {
    if (!renameClickTimerRef.current) return;
    clearTimeout(renameClickTimerRef.current);
    renameClickTimerRef.current = null;
  };

  const cancelRename = () => {
    setRenamingName('');
    setRenameValue('');
  };

  const cancelCreate = () => {
    setPendingCreate(null);
  };

  const submitCreate = async () => {
    if (!pendingCreate || renameSubmittingRef.current) return;
    const nextName = pendingCreate.name.trim();
    if (!nextName) {
      cancelCreate();
      return;
    }
    renameSubmittingRef.current = true;
    const label = pendingCreate.action === 'mkdir' ? '新建文件夹' : '新建文件';
    try {
      await createContainerFileEntry(node.id, container.id, { action: pendingCreate.action, path, name: nextName });
      setPendingCreate(null);
      await loadFiles(path);
      notify?.({ title: `${label}成功`, message: nextName });
    } catch (createError) {
      notify?.({ type: 'error', title: `${label}失败`, message: friendlyError(createError.message), duration: 4600 });
    } finally {
      renameSubmittingRef.current = false;
    }
  };

  const submitRename = async (item) => {
    if (renameSubmittingRef.current) return;
    const nextName = renameValue.trim();
    if (!nextName || nextName === item.name) {
      cancelRename();
      return;
    }
    renameSubmittingRef.current = true;
    await runFileAction('重命名', () => renameContainerFile(node.id, container.id, joinContainerPath(path, item.name), nextName));
    renameSubmittingRef.current = false;
    cancelRename();
  };

  const confirmRenameOnOutsidePointer = (event) => {
    if (!renamingName) return;
    const target = event.target instanceof Element ? event.target : null;
    if (target?.closest('.file-rename-input')) return;
    const item = items.find((entry) => entry.name === renamingName);
    if (item) submitRename(item);
  };

  const deleteItem = (item) => {
    if (readOnly) return;
    setDeleteDialog({ items: [item] });
  };

  const deleteSelected = () => {
    if (readOnly) return;
    if (!selectedItems.length) return;
    setDeleteDialog({ items: selectedItems });
  };

  const deleteContextSelection = (item) => {
    if (readOnly) return;
    if (selectedNames.includes(item.name) && selectedItems.length > 1) {
      setDeleteDialog({ items: selectedItems });
      return;
    }
    setDeleteDialog({ items: [item] });
  };

  const confirmDelete = () => {
    const itemsToDelete = deleteDialog?.items || [];
    if (!itemsToDelete.length) return;
    setDeleteDialog(null);
    runFileAction('删除', async () => {
      for (const item of itemsToDelete) {
        await deleteContainerFile(node.id, container.id, joinContainerPath(path, item.name));
      }
    });
  };

  const archiveOutputDirName = (name = '') => {
    const text = String(name || '').trim();
    return text
      .replace(/\.tar\.gz$/i, '')
      .replace(/\.tar\.bz2$/i, '')
      .replace(/\.tar\.xz$/i, '')
      .replace(/\.(zip|tar|tgz|tbz2|txz)$/i, '') || '解压文件';
  };

  const downloadSelected = () => {
    const file = selectedItems.find((item) => item.type !== 'd');
    if (file) downloadItem(file);
  };

  const openTextEditor = async (item) => {
    if (!item || item.type === 'd') return;
    if (!isEditableTextFile(item.name)) {
      notify?.({ title: '无法编辑该文件', message: '仅支持常见文本、配置和代码文件' });
      return;
    }
    const filePath = joinContainerPath(path, item.name);
    setEditorState({
      item,
      path: filePath,
      dir: path,
      content: '',
      draft: '',
      loading: true,
      saving: false,
      error: '',
    });
    closeContextMenu();
    try {
      const result = await fetchContainerFileContent(node.id, container.id, filePath);
      const text = base64ToText(result.contentBase64 || '');
      setEditorState((current) => current?.path === filePath ? {
        ...current,
        content: text,
        draft: text,
        loading: false,
      } : current);
    } catch (editorError) {
      setEditorState((current) => current?.path === filePath ? {
        ...current,
        loading: false,
        error: friendlyError(editorError.message),
      } : current);
    }
  };

  const saveTextEditor = async () => {
    if (!editorState || editorState.loading || editorState.saving || readOnly) return;
    setEditorState((current) => current ? { ...current, saving: true, error: '' } : current);
    try {
      await createContainerFileEntry(node.id, container.id, {
        action: 'upload',
        path: editorState.dir,
        name: editorState.item.name,
        contentBase64: textToBase64(editorState.draft),
      });
      setEditorState((current) => current ? { ...current, content: current.draft, saving: false } : current);
      await loadFiles(path);
      notify?.({ title: '文件已保存', message: editorState.item.name });
    } catch (saveError) {
      setEditorState((current) => current ? { ...current, saving: false, error: friendlyError(saveError.message) } : current);
      notify?.({ type: 'error', title: '保存失败', message: friendlyError(saveError.message), duration: 4600 });
    }
  };

  const prepareMonacoEditor = (monaco) => {
    defineDraculaMonacoTheme(monaco);
  };

  const mountMonacoEditor = (editor, monaco) => {
    monacoEditorRef.current = editor;
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
      saveTextEditor();
    });
    requestAnimationFrame(() => editor.layout());
  };

  useEffect(() => {
    if (!editorState || editorState.loading || editorState.error) return;
    const editor = monacoEditorRef.current;
    if (!editor) return;
    const layout = () => {
      try {
        editor.layout();
      } catch {
        // Monaco can be mid-dispose while the editor window is closing.
      }
    };
    layout();
    const frame = requestAnimationFrame(layout);
    const timer = setTimeout(layout, 180);
    return () => {
      cancelAnimationFrame(frame);
      clearTimeout(timer);
    };
  }, [editorState?.maximized, editorState?.loading, editorState?.error]);

  const editorDialog = editorState ? (
    <div
      className="code-editor-layer"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !editorState.saving) setEditorState(null);
        event.stopPropagation();
      }}
    >
      <section className={`code-editor-modal${editorState.maximized ? ' maximized' : ''}`} role="dialog" aria-modal="true" aria-label="文件编辑器">
        <div className="code-editor-titlebar">
          <div className="code-editor-path-title" title={editorState.path}>
            <i><FileText size={15} /></i>
            <span>{editorState.path}</span>
            {editorState.draft !== editorState.content && !readOnly && <i></i>}
          </div>
          <div className="code-editor-actions">
            {readOnly && <em>只读</em>}
            {editorState.draft !== editorState.content && !readOnly && <em>未保存</em>}
            <button disabled={readOnly || editorState.loading || editorState.saving || editorState.draft === editorState.content} onClick={saveTextEditor} type="button">
              {editorState.saving ? '保存中...' : '保存'}
            </button>
            <button
              aria-label={editorState.maximized ? '还原编辑器' : '放大编辑器'}
              className="icon"
              onClick={() => setEditorState((current) => current ? { ...current, maximized: !current.maximized } : current)}
              title={editorState.maximized ? '还原' : '放大'}
              type="button"
            >
              <span className={editorState.maximized ? 'window-icon restore' : 'window-icon maximize'} aria-hidden="true"></span>
            </button>
            <button aria-label="关闭编辑器" className="icon close" onClick={() => setEditorState(null)} type="button"><X size={18} /></button>
          </div>
        </div>
        <div className="code-editor-body">
          {editorState.loading ? (
            <div className="code-editor-loading"><i></i><span>正在读取文件</span></div>
          ) : editorState.error ? (
            <div className="code-editor-error">{editorState.error}</div>
          ) : (
            <Suspense fallback={<div className="code-editor-loading"><i></i><span>正在加载编辑器</span></div>}>
              <MonacoEditor
                beforeMount={prepareMonacoEditor}
                height="100%"
                language={getMonacoLanguage(editorState.item.name)}
                loading={<div className="code-editor-loading"><i></i><span>正在加载 Monaco</span></div>}
                onChange={(value) => setEditorState((current) => current ? { ...current, draft: value ?? '' } : current)}
                onMount={mountMonacoEditor}
                options={{
                  automaticLayout: true,
                  cursorBlinking: 'smooth',
                  cursorSmoothCaretAnimation: 'on',
                  fontFamily: '"Cascadia Mono", "JetBrains Mono", Consolas, monospace',
                  fontLigatures: true,
                  fontSize: 13,
                  lineHeight: 22,
                  minimap: { enabled: false },
                  padding: { top: 14, bottom: 14 },
                  readOnly,
                  renderLineHighlight: 'all',
                  scrollBeyondLastLine: false,
                  scrollbar: {
                    verticalScrollbarSize: 8,
                    horizontalScrollbarSize: 8,
                    useShadows: false,
                  },
                  smoothScrolling: true,
                  stickyScroll: { enabled: false },
                  tabSize: 2,
                  wordWrap: 'on',
                }}
                path={`file://${editorState.path}`}
                theme="beiming-dracula"
                value={editorState.draft}
                width="100%"
              />
            </Suspense>
          )}
        </div>
        <div className="code-editor-statusbar">
          <span>{editorState.draft !== editorState.content && !readOnly ? '未保存更改' : '已同步'}</span>
          <span>{getEditorLanguageLabel(editorState.item.name)}</span>
          <span>{Math.max(1, editorState.draft.split('\n').length)} 行</span>
        </div>
      </section>
    </div>
  ) : null;

  const copySelected = (mode = 'copy') => {
    if (!selectedItems.length) return;
    setFileClipboard({
      mode,
      items: selectedItems.map((item) => ({
        name: item.name,
        path: joinContainerPath(path, item.name),
        type: item.type,
      })),
    });
    notify?.({ title: mode === 'cut' ? '已剪切' : '已复制', message: `${selectedItems.length} 项` });
  };

  const pasteClipboard = () => {
    if (!fileClipboard?.items?.length) return;
    runFileAction(fileClipboard.mode === 'cut' ? '移动' : '复制', async () => {
      for (const item of fileClipboard.items) {
        if (fileClipboard.mode === 'cut') {
          if (item.path === path || joinContainerPath(path, item.name) === item.path) continue;
          await renameContainerFile(node.id, container.id, item.path, item.name, path);
        } else {
          await copyContainerFile(node.id, container.id, item.path, path);
        }
      }
      if (fileClipboard.mode === 'cut') setFileClipboard(null);
    });
  };

  const extractItems = (sourceItems) => {
    const archives = sourceItems.filter((item) => item.type !== 'd' && isArchiveFile(item.name));
    if (!archives.length) return;
    setExtractDialog({
      items: archives,
      targetPath: path,
      encoding: 'utf-8',
    });
  };

  const confirmExtract = () => {
    const archives = extractDialog?.items || [];
    if (!archives.length) return;
    const targetPath = normalizeContainerPath(extractDialog.targetPath || path);
    const encoding = extractDialog.encoding || 'utf-8';
    setExtractDialog(null);
    runFileAction('解压', async () => {
      for (const item of archives) {
        await extractContainerFile(node.id, container.id, joinContainerPath(path, item.name), targetPath, encoding);
      }
    });
  };

  const extractSelected = () => extractItems(selectedItems);

  const toggleSelectItem = (event, item) => {
    closeContextMenu();
    setSelectedNames((current) => {
      if (event.ctrlKey || event.metaKey) {
        return current.includes(item.name) ? current.filter((name) => name !== item.name) : [...current, item.name];
      }
      return current.includes(item.name) && current.length === 1 ? [] : [item.name];
    });
  };

  const downloadItem = async (item) => {
    if (downloadProgress) {
      notify?.({ title: '已有下载任务', message: '请等待当前下载完成或取消' });
      return;
    }
    const filePath = joinContainerPath(path, item.name);
    const controller = new AbortController();
    const downloadId = `${Date.now()}-${Math.random().toString(16).slice(2)}-${item.name}`;
    downloadAbortRef.current = controller;
    downloadIdRef.current = downloadId;
    try {
      const info = await fetchContainerFileDownloadInfo(node.id, container.id, filePath, downloadId);
      await downloadFileInRanges(filePath, info.name || item.name, info.size || Number(item.size || 0), controller.signal, downloadId);
      notify?.({ title: '下载完成', message: info.name || item.name });
    } catch (downloadError) {
      if (downloadError.name === 'AbortError') {
        notify?.({ title: '已取消下载', message: item.name });
      } else {
        notify?.({ type: 'error', title: '下载失败', message: friendlyError(downloadError.message), duration: 4600 });
      }
    } finally {
      if (downloadAbortRef.current === controller) downloadAbortRef.current = null;
      if (downloadIdRef.current === downloadId) downloadIdRef.current = '';
      setDownloadProgress(null);
    }
  };

  const cancelDownload = () => {
    const downloadId = downloadIdRef.current;
    downloadAbortRef.current?.abort();
    if (downloadId) cancelContainerFileDownload(node.id, container.id, downloadId).catch(() => undefined);
  };

  const downloadFileInRanges = async (filePath, fileName, fileSize, signal, downloadId) => {
    if (!fileSize) {
      setDownloadProgress({ current: fileName, percent: 100, speed: '0 B/s · 空文件' });
      saveBlobFile(new Blob([]), fileName);
      return;
    }
    const { chunkSize, workerCount } = getDownloadPlan(fileSize);
    const totalChunks = Math.max(1, Math.ceil(fileSize / chunkSize));
    let downloadedBytes = 0;
    let nextChunk = 0;
    let writable = null;
    let fileHandle = null;
    let writeQueue = Promise.resolve();
    const speedSamples = [];
    const chunks = new Array(totalChunks);
    if (window.showSaveFilePicker) {
      fileHandle = await window.showSaveFilePicker({ suggestedName: fileName });
      writable = await fileHandle.createWritable();
    }
    speedSamples.push([performance.now(), 0]);
    setDownloadProgress({ current: fileName, percent: 0, speed: `0 B/s · ${workerCount} 线程` });
    const updateProgress = (delta) => {
      const now = performance.now();
      downloadedBytes += delta;
      const percent = fileSize ? Math.min(99, Math.round((downloadedBytes / fileSize) * 100)) : 100;
      speedSamples.push([now, downloadedBytes]);
      while (speedSamples.length > 2 && now - speedSamples[0][0] > 1200) {
        speedSamples.shift();
      }
      const first = speedSamples[0] || [now, downloadedBytes];
      const elapsedSeconds = Math.max(0.12, (now - first[0]) / 1000);
      const realtimeBytes = Math.max(0, downloadedBytes - first[1]);
      setDownloadProgress((current) => ({
        ...(current || {}),
        percent,
        speed: `${formatRate(realtimeBytes / elapsedSeconds)} · ${workerCount} 线程`,
      }));
    };
    const worker = async () => {
      while (nextChunk < totalChunks) {
        if (signal.aborted) throw new DOMException('Download cancelled', 'AbortError');
        const chunkIndex = nextChunk;
        nextChunk += 1;
        const start = chunkIndex * chunkSize;
        const end = Math.min(fileSize - 1, start + chunkSize - 1);
        const pieces = [];
        let writePosition = start;
        await streamContainerFileRange(node.id, container.id, filePath, start, end, signal, downloadId, (piece) => {
          const position = writePosition;
          writePosition += piece.byteLength;
          if (writable) {
            writeQueue = writeQueue.then(() => writable.write({ type: 'write', position, data: piece }));
          } else {
            pieces.push(piece);
          }
          updateProgress(piece.byteLength);
        });
        if (!writable) chunks[chunkIndex] = new Blob(pieces);
      }
    };
    try {
      await Promise.all(Array.from({ length: workerCount }, worker));
      updateProgress(0);
      setDownloadProgress((current) => ({ ...(current || {}), percent: 100 }));
      if (writable) {
        await writeQueue;
        await writable.close();
      } else {
        saveBlobFile(new Blob(chunks), fileName);
      }
    } catch (error) {
      if (writable) await writable.abort().catch(() => undefined);
      throw error;
    }
  };

  const segments = path.split('/').filter(Boolean);
  const goBack = () => {
    setPathHistory((current) => {
      if (!current.back.length) return current;
      const nextPath = current.back[current.back.length - 1];
      setPath(nextPath);
      return {
        back: current.back.slice(0, -1),
        forward: [path, ...current.forward],
      };
    });
  };
  const goForward = () => {
    setPathHistory((current) => {
      if (!current.forward.length) return current;
      const nextPath = current.forward[0];
      setPath(nextPath);
      return {
        back: [...current.back, path],
        forward: current.forward.slice(1),
      };
    });
  };

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        className={externalDragging ? 'container-files-modal dragging-files' : 'container-files-modal'}
        onDragEnter={handleFilePanelDragOver}
        onDragLeave={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget)) {
            clearDropHint();
            setExternalDragging(false);
            setDropTarget('');
            if (dragItem) stopDragging();
          }
        }}
        onDragOver={handleFilePanelDragOver}
        onDrop={(event) => handleNativeDrop(event, path)}
        role="dialog"
        aria-modal="true"
        aria-label="容器文件管理"
        onClick={closeContextMenu}
        onMouseDown={startSelectionBox}
      >
      <div className="files-head">
        <div className="files-nav-controls">
          <button disabled={!pathHistory.back.length} onClick={goBack} type="button"><ChevronLeft size={18} /></button>
          <button disabled={!pathHistory.forward.length} onClick={goForward} type="button"><ChevronRight size={18} /></button>
          <button disabled={busy || loading} onClick={() => loadFiles(path)} type="button"><RotateCw size={17} /></button>
        </div>
        <div className="files-location-box">
          <div className="files-breadcrumb" aria-label="当前路径" ref={breadcrumbRef}>
            <button
              className={[
                segments.length === 0 ? 'active' : '',
                dropTarget === '/' ? 'drop-target' : '',
              ].filter(Boolean).join(' ')}
              onClick={() => navigateToPath('/')}
              onDragEnter={(event) => handleFolderDragOver(event, '/', '/', false)}
              onDragLeave={(event) => leaveDropTarget(event, '/')}
              onDragOver={(event) => handleFolderDragOver(event, '/', '/', false)}
              onDrop={(event) => handleNativeDrop(event, '/')}
              type="button"
            >
              /
            </button>
            {segments.map((segment, index) => {
              const targetPath = `/${segments.slice(0, index + 1).join('/')}`;
              const active = index === segments.length - 1;
              return (
                <span key={targetPath}>
                  <ChevronRight size={15} />
                  <button
                    className={[
                      active ? 'active' : '',
                      dropTarget === targetPath ? 'drop-target' : '',
                      dragItem?.path === targetPath ? 'drop-invalid' : '',
                    ].filter(Boolean).join(' ')}
                    onClick={() => navigateToPath(targetPath)}
                    onDragEnter={(event) => handleFolderDragOver(event, targetPath, segment, dragItem?.path === targetPath)}
                    onDragLeave={(event) => leaveDropTarget(event, targetPath)}
                    onDragOver={(event) => handleFolderDragOver(event, targetPath, segment, dragItem?.path === targetPath)}
                    onDrop={(event) => handleNativeDrop(event, targetPath)}
                    type="button"
                  >
                    {segment}
                  </button>
                </span>
              );
            })}
          </div>
        </div>
        <label className="files-search-box">
          <Search size={18} />
          <input value={searchQuery} onChange={(event) => setSearchQuery(event.target.value)} placeholder="搜索" />
        </label>
        <button className="files-window-close" onClick={onClose} type="button"><X size={19} /></button>
      </div>
      <div className="files-toolbar">
        <div className="files-toolbar-group files-create-actions">
          <button disabled={!canWriteFiles || busy} onClick={() => uploadRef.current?.click()} type="button"><Upload size={17} />上传文件</button>
          <button disabled={!canWriteFiles || busy} onClick={() => createEntry('mkdir')} type="button"><Folder size={17} />新建文件夹</button>
          <button disabled={!canWriteFiles || busy} onClick={() => createEntry('touch')} type="button"><FileText size={17} />新建文件</button>
        </div>
        <div className="files-toolbar-group files-operate-actions">
          <button disabled={!canWriteFiles || busy || !selectedItems.some((item) => item.type !== 'd')} onClick={downloadSelected} type="button"><Download size={17} />下载</button>
          <button disabled={!canWriteFiles || busy || selectedItems.length === 0} onClick={() => copySelected('copy')} type="button"><Copy size={17} />复制</button>
          <button disabled={!canWriteFiles || busy || selectedItems.length === 0} onClick={() => copySelected('cut')} type="button"><Scissors size={17} />剪切</button>
          <button disabled={!canWriteFiles || busy || !fileClipboard?.items?.length} onClick={pasteClipboard} type="button"><Clipboard size={17} />粘贴</button>
          <button disabled={!canWriteFiles || busy || !archiveSelected} onClick={extractSelected} type="button"><PackageOpen size={17} />解压</button>
          <button disabled={!canWriteFiles || busy || selectedItems.length === 0} onClick={deleteSelected} type="button"><Trash2 size={17} />删除</button>
        </div>
        <input hidden multiple onChange={uploadFile} ref={uploadRef} type="file" />
      </div>
      <div
        className="files-table"
        onContextMenu={openBlankContextMenu}
        onMouseDownCapture={confirmRenameOnOutsidePointer}
        onDragLeave={(event) => {
          if (event.currentTarget.contains(event.relatedTarget)) return;
          clearDropHint();
          setExternalDragging(false);
          setDropTarget('');
        }}
        ref={filesTableRef}
      >
        {externalDragging && <div className="files-drop-hint">松开即可上传到当前目录</div>}
        <div className="files-row files-row-head" style={fileColumnStyle}>
          {FILE_COLUMNS.map(({ key, label }) => (
            <div className="files-head-cell" key={key}>
              <button className={fileSort.key === key ? `active ${fileSort.direction}` : ''} onClick={() => changeFileSort(key)} type="button">
                <span>{label}</span>
                <ChevronDown size={14} />
              </button>
              <span
                aria-hidden="true"
                className="files-column-resizer"
                onMouseDown={(event) => startFileColumnResize(event, key)}
              />
            </div>
          ))}
        </div>
        {pendingCreate && (
          <div className="files-row file-entry selected creating" data-file-kind={pendingCreate.type === 'd' ? 'dir' : 'file'} style={fileColumnStyle}>
            <button className="file-name" type="button">
              <FileVisualIcon isDir={pendingCreate.type === 'd'} name={pendingCreate.name} />
              <input
                autoFocus
                className="file-rename-input"
                onBlur={submitCreate}
                onChange={(event) => setPendingCreate((current) => current ? { ...current, name: event.target.value } : current)}
                onClick={(event) => event.stopPropagation()}
                onFocus={(event) => event.target.select()}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') submitCreate();
                  if (event.key === 'Escape') cancelCreate();
                }}
                style={{ width: `${Math.max(8, Math.min(42, pendingCreate.name.length + 2))}ch` }}
                value={pendingCreate.name}
              />
            </button>
            <span>-</span>
            <span>{pendingCreate.type === 'd' ? '文件夹' : '文件'}</span>
            <span>-</span>
          </div>
        )}
        {visibleItems.map((item) => {
          const isDir = item.type === 'd';
          const selected = selectedNames.includes(item.name);
          const itemPath = joinContainerPath(path, item.name);
          const invalidDrop = isDir && dragItem?.path === itemPath;
          return (
            <div
              className={[
                'files-row file-entry',
                selected ? 'selected' : '',
                dragItem?.path === itemPath ? 'dragging' : '',
                isDir && dropTarget === itemPath ? 'drop-target' : '',
                invalidDrop && dropTarget === itemPath ? 'drop-invalid' : '',
              ].filter(Boolean).join(' ')}
              data-file-kind={isDir ? 'dir' : 'file'}
              data-file-name={item.name}
              draggable={!renamingName && !readOnly}
              key={`${item.name}-${item.modified}`}
              onClick={(event) => toggleSelectItem(event, item)}
              onContextMenu={(event) => openContextMenu(event, item)}
              onDoubleClick={() => renamingName ? undefined : isDir ? navigateToPath(itemPath) : isEditableTextFile(item.name) ? openTextEditor(item) : undefined}
              onDragEnd={stopDragging}
              onDragLeave={isDir ? (event) => leaveDropTarget(event, itemPath) : undefined}
              onDragStart={(event) => {
                if (renamingName) {
                  event.preventDefault();
                  return;
                }
                startItemDrag(event, item);
              }}
              onDrop={isDir ? (event) => handleNativeDrop(event, itemPath) : undefined}
              onDragEnter={isDir ? (event) => handleFolderDragOver(event, itemPath, item.name, invalidDrop) : clearInternalFileDropTarget}
              onDragOver={isDir ? (event) => handleFolderDragOver(event, itemPath, item.name, invalidDrop) : clearInternalFileDropTarget}
              style={fileColumnStyle}
            >
              <button className="file-name" onClick={(event) => {
                if (renamingName === item.name) event.stopPropagation();
                else scheduleRenameFromNameClick(event, item, selected);
              }} onDoubleClick={() => {
                cancelScheduledRename();
                return renamingName ? undefined : isDir ? navigateToPath(itemPath) : isEditableTextFile(item.name) ? openTextEditor(item) : undefined;
              }} type="button">
                <FileVisualIcon isDir={isDir} name={item.name} />
                {renamingName === item.name ? (
                  <input
                    autoFocus
                    className="file-rename-input"
                    onBlur={() => submitRename(item)}
                    onChange={(event) => setRenameValue(event.target.value)}
                    onClick={(event) => event.stopPropagation()}
                    onFocus={(event) => event.target.select()}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') submitRename(item);
                      if (event.key === 'Escape') cancelRename();
                    }}
                    style={{ width: `${Math.max(8, Math.min(42, renameValue.length + 2))}ch` }}
                    value={renameValue}
                  />
                ) : (
                  <span title={item.name}>{item.name}</span>
                )}
              </button>
              <span>{formatFileTime(item.modified)}</span>
              <span>{formatFileKind(item.name, isDir)}</span>
              <span>{isDir ? '-' : formatBytes(Number(item.size || 0))}</span>
            </div>
          );
        })}
        {loading && (
          <div className="files-loading-state">
            <i></i>
            <span>加载中</span>
          </div>
        )}
        {!loading && error && <div className="files-empty error">{error}</div>}
        {!loading && !error && items.length === 0 && !pendingCreate && <div className="files-empty">当前目录为空</div>}
        {!loading && !error && items.length > 0 && visibleItems.length === 0 && !pendingCreate && <div className="files-empty">没有匹配的文件</div>}
        {selectionBox?.active && (
          <div
            className="file-selection-box"
            style={{
              left: `${Math.min(selectionBox.startX, selectionBox.x)}px`,
              top: `${Math.min(selectionBox.startY, selectionBox.y)}px`,
              width: `${Math.abs(selectionBox.x - selectionBox.startX)}px`,
              height: `${Math.abs(selectionBox.y - selectionBox.startY)}px`,
            }}
          ></div>
        )}
      </div>
      <div className="files-statusbar">
        {!running && offlineWritable
          ? `挂载目录 · 离线可编辑 · 选中 ${selectedItems.length} 项（共 ${items.length} 项）`
          : readOnly
            ? `只读快照 · 选中 ${selectedItems.length} 项（共 ${items.length} 项）`
            : `选中 ${selectedItems.length} 项（共 ${items.length} 项）`}
      </div>
      {contextMenu && (
        <div className="files-context-menu" style={{ left: contextMenu.x, top: contextMenu.y }} onClick={(event) => event.stopPropagation()}>
          {!contextMenu.item ? (
            <>
              <button disabled={!canWriteFiles || busy} onClick={() => { uploadRef.current?.click(); closeContextMenu(); }} type="button">
                <Upload size={16} />上传文件
              </button>
              <button disabled={!canWriteFiles || busy} onClick={() => { createEntry('mkdir'); closeContextMenu(); }} type="button">
                <Folder size={16} />新建文件夹
              </button>
              <button disabled={!canWriteFiles || busy} onClick={() => { createEntry('touch'); closeContextMenu(); }} type="button">
                <FileText size={16} />新建文件
              </button>
              <span className="context-menu-separator" />
              <button disabled={!canWriteFiles || busy || !fileClipboard?.items?.length} onClick={() => { pasteClipboard(); closeContextMenu(); }} type="button">
                <Clipboard size={16} />粘贴
              </button>
              <button disabled={loading} onClick={() => { loadFiles(path); closeContextMenu(); }} type="button">
                <RotateCw size={16} />刷新
              </button>
            </>
          ) : (
            <>
              {contextMenu.item.type === 'd' && (
                <button onClick={() => { navigateToPath(joinContainerPath(path, contextMenu.item.name)); closeContextMenu(); }} type="button">
                  <Folder size={16} />打开
                </button>
              )}
              {contextMenu.item.type !== 'd' && isEditableTextFile(contextMenu.item.name) && (
                <button disabled={busy} onClick={() => { openTextEditor(contextMenu.item); closeContextMenu(); }} type="button">
                  <FileText size={16} />编辑
                </button>
              )}
              {contextMenu.item.type !== 'd' && (
                <button disabled={!canWriteFiles} onClick={() => { downloadItem(contextMenu.item); closeContextMenu(); }} type="button">
                  <Download size={16} />下载
                </button>
              )}
              <span className="context-menu-separator" />
              <button disabled={!canWriteFiles} onClick={() => { copySelected('copy'); closeContextMenu(); }} type="button">
                <Copy size={16} />复制
              </button>
              <button disabled={!canWriteFiles} onClick={() => { copySelected('cut'); closeContextMenu(); }} type="button">
                <Scissors size={16} />剪切
              </button>
              {fileClipboard?.items?.length && (
                <button disabled={!canWriteFiles} onClick={() => { pasteClipboard(); closeContextMenu(); }} type="button">
                  <Clipboard size={16} />粘贴
                </button>
              )}
              {contextMenu.item.type !== 'd' && isArchiveFile(contextMenu.item.name) && (
                <button disabled={!canWriteFiles} onClick={() => { extractItems([contextMenu.item]); closeContextMenu(); }} type="button">
                  <PackageOpen size={16} />解压
                </button>
              )}
              <span className="context-menu-separator" />
              <button disabled={!canWriteFiles} onClick={() => { startRenameItem(contextMenu.item); closeContextMenu(); }} type="button">
                <PencilLine size={16} />重命名
              </button>
              <button className="danger" disabled={!canWriteFiles} onClick={() => { deleteContextSelection(contextMenu.item); closeContextMenu(); }} type="button">
                <Trash2 size={16} />删除
              </button>
            </>
          )}
        </div>
      )}
      {dropHint && (
        <div className={dropHint.invalid ? 'file-drop-tooltip invalid' : 'file-drop-tooltip'} style={{ left: dropHint.x, top: dropHint.y }}>
          <i>{dropHint.invalid ? '!' : '✓'}</i>
          <span>{dropHint.text}</span>
        </div>
      )}
      {uploadProgress && (
        <div className="file-upload-progress">
          <div className="file-upload-copy">
            <strong>正在上传</strong>
            <span title={uploadProgress.current}>{uploadProgress.current}</span>
            <small>{uploadProgress.index}/{uploadProgress.total} · {uploadProgress.speed}</small>
          </div>
          <div className="file-upload-side">
            <b>{uploadProgress.percent}%</b>
            <button onClick={cancelUpload} type="button">取消</button>
          </div>
          <i><em style={{ width: `${uploadProgress.percent}%` }}></em></i>
        </div>
      )}
      {downloadProgress && (
        <div className={uploadProgress ? 'file-upload-progress file-download-progress stacked' : 'file-upload-progress file-download-progress'}>
          <div className="file-upload-copy">
            <strong>正在下载</strong>
            <span title={downloadProgress.current}>{downloadProgress.current}</span>
            <small>{downloadProgress.speed}</small>
          </div>
          <div className="file-upload-side">
            <b>{downloadProgress.percent}%</b>
            <button onClick={cancelDownload} type="button">取消</button>
          </div>
          <i><em style={{ width: `${downloadProgress.percent}%` }}></em></i>
        </div>
      )}
      {uploadConfirm && (
        <div
          className="file-confirm-layer"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setUploadConfirm(null);
            event.stopPropagation();
          }}
        >
          <div className="file-confirm-dialog file-upload-confirm-dialog" role="dialog" aria-modal="true" aria-label="替换文件确认">
            <div className="file-confirm-head">
              <i><Upload size={15} /></i>
              <div className="file-confirm-title">
                <strong>{uploadConfirm.conflicts.length === 1 ? '目标已包含同名文件' : `${uploadConfirm.conflicts.length} 个文件已存在`}</strong>
                <span>{uploadConfirm.targetPath}</span>
              </div>
              <button onClick={() => setUploadConfirm(null)} type="button"><X size={21} /></button>
            </div>
            <p>如果继续上传，远端目录中的同名文件会被替换。</p>
            <div className="upload-conflict-list">
              {uploadConfirm.conflicts.slice(0, 5).map((file) => (
                <div key={file.name}>
                  <FileText size={15} />
                  <span title={file.name}>{file.name}</span>
                  <em>{formatBytes(file.size)}</em>
                </div>
              ))}
              {uploadConfirm.conflicts.length > 5 && <small>还有 {uploadConfirm.conflicts.length - 5} 个同名文件</small>}
            </div>
            <div className="file-confirm-actions">
              <button onClick={() => setUploadConfirm(null)} type="button">取消</button>
              <button className="primary" onClick={confirmOverwriteUpload} type="button">替换文件</button>
            </div>
          </div>
        </div>
      )}
      {editorDialog && createPortal(editorDialog, document.body)}
      {deleteDialog && (
        <div
          className="file-confirm-layer"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setDeleteDialog(null);
            event.stopPropagation();
          }}
        >
          <div className="file-confirm-dialog" role="dialog" aria-modal="true" aria-label="删除文件确认">
            <div className="file-confirm-head">
              <i>i</i>
              <strong>{deleteDialog.items.length === 1 ? `删除 ${deleteDialog.items[0].name}` : `删除 ${deleteDialog.items.length} 项`}</strong>
              <button onClick={() => setDeleteDialog(null)} type="button"><X size={21} /></button>
            </div>
            <p>确定要删除所选文件？删除后无法恢复。</p>
            <div className="file-confirm-actions">
              <button onClick={() => setDeleteDialog(null)} type="button">取消</button>
              <button className="danger" onClick={confirmDelete} type="button">彻底删除</button>
            </div>
          </div>
        </div>
      )}
      {extractDialog && (
        <div
          className="file-confirm-layer"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setExtractDialog(null);
            event.stopPropagation();
          }}
        >
          <div className="file-confirm-dialog file-extract-dialog" role="dialog" aria-modal="true" aria-label="解压文件">
            <div className="file-confirm-head">
              <i><PackageOpen size={15} /></i>
              <div className="file-extract-title">
                <strong>{extractDialog.items.length === 1 ? `解压 ${extractDialog.items[0].name}` : `解压 ${extractDialog.items.length} 个压缩包`}</strong>
                <span>远端 Daemon 将在节点侧完成解压</span>
              </div>
              <button onClick={() => setExtractDialog(null)} type="button"><X size={21} /></button>
            </div>
            <div className="file-extract-form">
              <label className="file-extract-path-field">
                <span>目标目录</span>
                <input
                  value={extractDialog.targetPath}
                  onChange={(event) => setExtractDialog((current) => current ? { ...current, targetPath: event.target.value } : current)}
                  placeholder="/"
                />
              </label>
              <div className="file-extract-shortcuts">
                <button onClick={() => setExtractDialog((current) => current ? { ...current, targetPath: path } : current)} type="button">当前目录</button>
                {extractDialog.items.length === 1 && (
                  <button onClick={() => setExtractDialog((current) => current ? { ...current, targetPath: joinContainerPath(path, archiveOutputDirName(current.items[0]?.name)) } : current)} type="button">同名目录</button>
                )}
              </div>
              <div className="file-extract-encoding">
                <span>文件名编码</span>
                <div>
                  {[
                    ['utf-8', 'UTF-8'],
                    ['gbk', 'GBK'],
                  ].map(([value, label]) => (
                    <button
                      className={extractDialog.encoding === value ? 'active' : ''}
                      key={value}
                      onClick={() => setExtractDialog((current) => current ? { ...current, encoding: value } : current)}
                      type="button"
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>
              <p><CheckCircle2 size={14} />ZIP 压缩包会按所选编码解码文件名；TAR 系列通常使用归档内原始文件名。</p>
            </div>
            <div className="file-confirm-actions">
              <button onClick={() => setExtractDialog(null)} type="button">取消</button>
              <button className="primary" onClick={confirmExtract} type="button">开始解压</button>
            </div>
          </div>
        </div>
      )}
      </section>
    </div>
  );
}

function PortMappingChip({ ports = [] }) {
  const visiblePorts = (ports || []).slice(0, 2);
  return (
    <div className="info-chip port-chip">
      <span>端口映射</span>
      {visiblePorts.length === 0 ? (
        <strong>未映射</strong>
      ) : (
        <div className="port-map-list">
          {visiblePorts.map((port) => {
            const host = formatHostPort(port.host);
            const target = parseContainerPort(port.containerPort);
            return (
              <div className="port-map" key={`${port.containerPort}-${port.host || 'none'}`} title={formatPortTitle(port)}>
                <b title={host}>{host}</b>
                <i>→</i>
                <em title={target.port}>{target.port}</em>
                {target.protocol && <small>{target.protocol.toUpperCase()}</small>}
              </div>
            );
          })}
          {ports.length > visiblePorts.length && <div className="port-more">+{ports.length - visiblePorts.length}</div>}
        </div>
      )}
    </div>
  );
}

function FileVisualIcon({ isDir, name }) {
  if (isDir) return <span className="file-asset-icon win-folder" aria-hidden="true"></span>;
  const extension = getFileExtension(name);
  const iconMap = {
    css: 'file-css.png',
    html: 'file-html.png',
    jar: 'file-jar.png',
    js: 'file-js.png',
    json: 'file-json.png',
    log: 'file-log.png',
    md: 'file-md.png',
    properties: 'file-properties.png',
    tar: 'file-tar.png',
    tgz: 'file-tar.png',
    txt: 'file-txt.png',
    ts: 'file-ts.png',
    yaml: 'file-yaml.png',
    yml: 'file-yml.png',
    zip: 'file-zip.png',
  };
  const icon = iconMap[extension] || 'windows-file.png';
  return <span className="file-asset-icon win-file" style={{ '--file-icon': `url("/icons/${icon}")` }} aria-hidden="true"></span>;
}

function isArchiveFile(name = '') {
  return /\.(zip|tar|tgz|tar\.gz|tar\.bz2|tbz2|tar\.xz|txz)$/i.test(String(name));
}

function getFileExtension(name = '') {
  const lower = String(name || '').toLowerCase();
  if (lower.endsWith('.tar.gz')) return 'tgz';
  if (lower.endsWith('.tar.bz2')) return 'tar';
  if (lower.endsWith('.tar.xz')) return 'tar';
  return lower.includes('.') ? lower.split('.').pop() : '';
}

function formatFileKind(name = '', isDir = false) {
  if (isDir) return '文件夹';
  const text = String(name || '');
  const lower = text.toLowerCase();
  if (lower.endsWith('.tar.gz')) return 'GZ 压缩包';
  if (lower.endsWith('.tar.bz2')) return 'BZ2 压缩包';
  if (lower.endsWith('.tar.xz')) return 'XZ 压缩包';
  const ext = lower.includes('.') ? lower.split('.').pop() : '';
  const labels = {
    json: 'JSON 源文件',
    txt: '文本文档',
    log: '日志文件',
    properties: 'Properties 源文件',
    jar: 'JAR 文件',
    zip: 'ZIP 压缩包',
    tar: 'TAR 压缩包',
    yml: 'YAML 源文件',
    yaml: 'YAML 源文件',
    js: 'JavaScript 源文件',
    ts: 'TypeScript 源文件',
    css: 'CSS 源文件',
    html: 'HTML 文档',
    md: 'Markdown 文档',
    sh: 'Shell 脚本',
  };
  return labels[ext] || (ext ? `${ext.toUpperCase()} 文件` : '文件');
}

function base64ToText(value = '') {
  const binary = atob(String(value || ''));
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return new TextDecoder('utf-8').decode(bytes);
}

function textToBase64(value = '') {
  const bytes = new TextEncoder().encode(String(value || ''));
  let binary = '';
  const chunkSize = 0x8000;
  for (let index = 0; index < bytes.length; index += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(index, index + chunkSize));
  }
  return btoa(binary);
}

function getEditorLanguage(fileName = '') {
  const ext = getFileExtension(fileName);
  if (['json'].includes(ext)) return 'json';
  if (['html', 'xml'].includes(ext)) return 'html';
  if (['css'].includes(ext)) return 'css';
  if (['yaml', 'yml'].includes(ext)) return 'yaml';
  if (['sh', 'bash'].includes(ext)) return 'shell';
  if (['properties', 'conf', 'cfg', 'ini', 'env'].includes(ext)) return 'properties';
  if (['js', 'jsx', 'ts', 'tsx', 'mjs', 'cjs'].includes(ext)) return 'javascript';
  return 'text';
}

function getMonacoLanguage(fileName = '') {
  const ext = getFileExtension(fileName);
  const languageMap = {
    bash: 'shell',
    c: 'c',
    cfg: 'ini',
    conf: 'ini',
    config: 'ini',
    cpp: 'cpp',
    css: 'css',
    env: 'ini',
    go: 'go',
    h: 'c',
    hpp: 'cpp',
    html: 'html',
    htm: 'html',
    ini: 'ini',
    java: 'java',
    js: 'javascript',
    json: 'json',
    jsonc: 'json',
    jsx: 'javascript',
    less: 'less',
    log: 'log',
    lua: 'lua',
    mjs: 'javascript',
    cjs: 'javascript',
    md: 'markdown',
    php: 'php',
    properties: 'ini',
    py: 'python',
    rb: 'ruby',
    rs: 'rust',
    scss: 'scss',
    sh: 'shell',
    sql: 'sql',
    toml: 'ini',
    ts: 'typescript',
    tsx: 'typescript',
    txt: 'plaintext',
    xml: 'xml',
    yaml: 'yaml',
    yml: 'yaml',
  };
  const baseName = String(fileName || '').toLowerCase().split('/').pop();
  if (baseName === 'dockerfile') return 'dockerfile';
  if (baseName === 'makefile') return 'makefile';
  return languageMap[ext] || 'plaintext';
}

function defineDraculaMonacoTheme(monaco) {
  if (!monaco?.editor) return;
  monaco.editor.defineTheme('beiming-dracula', {
    base: 'vs-dark',
    inherit: true,
    rules: [
      { token: '', foreground: 'f8f8f2', background: '282a36' },
      { token: 'comment', foreground: '6272a4', fontStyle: 'italic' },
      { token: 'string', foreground: 'f1fa8c' },
      { token: 'number', foreground: 'bd93f9' },
      { token: 'regexp', foreground: 'ffb86c' },
      { token: 'keyword', foreground: 'ff79c6' },
      { token: 'operator', foreground: 'ff79c6' },
      { token: 'namespace', foreground: 'f8f8f2' },
      { token: 'type', foreground: '8be9fd', fontStyle: 'italic' },
      { token: 'struct', foreground: '8be9fd' },
      { token: 'class', foreground: '8be9fd' },
      { token: 'interface', foreground: '8be9fd' },
      { token: 'function', foreground: '50fa7b' },
      { token: 'method', foreground: '50fa7b' },
      { token: 'variable', foreground: 'f8f8f2' },
      { token: 'variable.predefined', foreground: 'bd93f9' },
      { token: 'constant', foreground: 'bd93f9' },
      { token: 'tag', foreground: 'ff79c6' },
      { token: 'attribute.name', foreground: '50fa7b' },
      { token: 'attribute.value', foreground: 'f1fa8c' },
      { token: 'delimiter', foreground: 'f8f8f2' },
    ],
    colors: {
      'editor.background': '#282a36',
      'editor.foreground': '#f8f8f2',
      'editorLineNumber.foreground': '#6272a4',
      'editorLineNumber.activeForeground': '#f8f8f2',
      'editorCursor.foreground': '#ff79c6',
      'editor.selectionBackground': '#5f4b8b',
      'editor.selectionForeground': '#ffffff',
      'editor.inactiveSelectionBackground': '#4b3a6d',
      'editor.wordHighlightBackground': '#4d5368',
      'editor.wordHighlightStrongBackground': '#5f4b8b',
      'editor.lineHighlightBackground': '#303241',
      'editor.lineHighlightBorder': '#00000000',
      'editorIndentGuide.background1': '#3b3f51',
      'editorIndentGuide.activeBackground1': '#6272a4',
      'editorWhitespace.foreground': '#44475a',
      'editorGutter.background': '#252733',
      'editorWidget.background': '#21222c',
      'editorWidget.border': '#44475a',
      'editorSuggestWidget.background': '#21222c',
      'editorSuggestWidget.border': '#44475a',
      'editorSuggestWidget.foreground': '#f8f8f2',
      'editorSuggestWidget.selectedBackground': '#44475a',
      'editorHoverWidget.background': '#21222c',
      'editorHoverWidget.border': '#44475a',
      'scrollbarSlider.background': '#6272a466',
      'scrollbarSlider.hoverBackground': '#6272a488',
      'scrollbarSlider.activeBackground': '#8be9fd66',
    },
  });
}

function isEditableTextFile(fileName = '') {
  const lower = String(fileName || '').toLowerCase();
  const baseName = lower.split('/').pop();
  const knownNames = new Set([
    '.env',
    '.gitignore',
    '.npmrc',
    '.yarnrc',
    'dockerfile',
    'makefile',
    'readme',
    'license',
  ]);
  if (knownNames.has(baseName)) return true;
  const ext = getFileExtension(lower);
  return new Set([
    'txt',
    'log',
    'md',
    'json',
    'jsonc',
    'yaml',
    'yml',
    'xml',
    'html',
    'htm',
    'css',
    'scss',
    'less',
    'js',
    'jsx',
    'ts',
    'tsx',
    'mjs',
    'cjs',
    'sh',
    'bash',
    'env',
    'properties',
    'conf',
    'config',
    'cfg',
    'ini',
    'toml',
    'sql',
    'py',
    'go',
    'java',
    'php',
    'rb',
    'lua',
    'rs',
    'c',
    'cpp',
    'h',
    'hpp',
  ]).has(ext);
}

function getEditorLanguageLabel(fileName = '') {
  const labels = {
    json: 'JSON',
    html: 'HTML',
    css: 'CSS',
    yaml: 'YAML',
    shell: 'Shell',
    properties: 'Properties',
    javascript: 'JavaScript',
    text: 'Plain Text',
  };
  return labels[getEditorLanguage(fileName)] || 'Plain Text';
}

function ContainerCreateWizard({ node, notify, onClose, onCreated }) {
  useBodyScrollLock();
  const [step, setStep] = useState(0);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [createProgress, setCreateProgress] = useState(null);
  const createSocketRef = useRef(null);
  const [imageQuery, setImageQuery] = useState('');
  const [imageResults, setImageResults] = useState([]);
  const [localImages, setLocalImages] = useState([]);
  const [imageLoading, setImageLoading] = useState(false);
  const [imageSearchOpen, setImageSearchOpen] = useState(false);
  const [restartOpen, setRestartOpen] = useState(false);
  const [networkOpen, setNetworkOpen] = useState(false);
  const [envOpen, setEnvOpen] = useState(false);
  const [mountsOpen, setMountsOpen] = useState(false);
  const [resourceOpen, setResourceOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [importCommand, setImportCommand] = useState('');
  const [importError, setImportError] = useState('');
  const imageSearchRef = useRef(null);
  const restartRef = useRef(null);
  const networkRef = useRef(null);
  const [form, setForm] = useState({
    name: '',
    image: '',
    restartPolicy: 'unless-stopped',
    networkMode: 'bridge',
    ports: [],
    env: [],
    mounts: [],
    workingDir: '',
    command: '',
    privileged: false,
    cpuLimit: '',
    memoryLimit: '',
    networkDownloadLimit: '',
    networkUploadLimit: '',
    stdinOpen: true,
    tty: true,
  });
  const steps = ['基础', '镜像', '网络', '高级', '确认'];
  const restartOptions = [
    { value: 'no', label: 'No' },
    { value: 'always', label: 'Always' },
    { value: 'unless-stopped', label: 'Unless stopped' },
    { value: 'on-failure', label: 'On failure' },
  ];
  const networkOptions = [
    { value: 'bridge', label: 'bridge' },
    { value: 'host', label: 'host' },
    { value: 'none', label: 'none' },
  ];
  const canNext = step === 0 ? form.name.trim() : step === 1 ? form.image.trim() : true;
  const setField = (field, value) => setForm((current) => ({ ...current, [field]: value }));
  const resourceCount = [form.cpuLimit, form.memoryLimit, form.networkDownloadLimit, form.networkUploadLimit].filter(Boolean).length;
  const buildCreatePayload = () => {
    const payload = {
      name: form.name.trim(),
      image: form.image.trim(),
      restartPolicy: form.restartPolicy,
      networkMode: form.networkMode,
      ports: editRowsToPortSpecs(form.ports),
      env: editRowsToEnvSpecs(form.env),
      mounts: editRowsToMountSpecs(form.mounts),
      privileged: form.privileged,
      cpuLimit: form.cpuLimit ? Number(form.cpuLimit) : 0,
      memoryLimit: form.memoryLimit,
      networkDownloadLimit: form.networkDownloadLimit,
      networkUploadLimit: form.networkUploadLimit,
      stdinOpen: form.stdinOpen,
      tty: form.tty,
    };
    if (form.workingDir.trim()) payload.workingDir = form.workingDir.trim();
    if (form.command.trim()) payload.command = form.command.trim();
    return payload;
  };

  useEffect(() => () => {
    if (createSocketRef.current) {
      createSocketRef.current.disconnect();
      createSocketRef.current = null;
    }
  }, []);

  useEffect(() => {
    let ignore = false;
    fetchImages(node.id)
      .then((items) => {
        if (!ignore) setLocalImages(items || []);
      })
      .catch(() => {
        if (!ignore) setLocalImages([]);
      });
    return () => {
      ignore = true;
    };
  }, [node.id]);

  useEffect(() => {
    const keyword = imageQuery.trim();
    if (keyword.length < 2) {
      setImageResults([]);
      return undefined;
    }
    let ignore = false;
    const timer = setTimeout(async () => {
      setImageLoading(true);
      try {
        const results = await searchDockerImages(keyword);
        if (!ignore) setImageResults(results || []);
      } catch {
        if (!ignore) setImageResults([]);
      } finally {
        if (!ignore) setImageLoading(false);
      }
    }, 260);
    return () => {
      ignore = true;
      clearTimeout(timer);
    };
  }, [imageQuery]);

  useEffect(() => {
    const closePopovers = (event) => {
      if (imageSearchRef.current && !imageSearchRef.current.contains(event.target)) {
        setImageSearchOpen(false);
      }
      if (restartRef.current && !restartRef.current.contains(event.target)) {
        setRestartOpen(false);
      }
      if (networkRef.current && !networkRef.current.contains(event.target)) {
        setNetworkOpen(false);
      }
    };
    document.addEventListener('mousedown', closePopovers);
    return () => document.removeEventListener('mousedown', closePopovers);
  }, []);

  const localImageNames = useMemo(() => new Set(localImages.map((image) => image.name)), [localImages]);
  const localImageRepos = useMemo(() => new Set(localImages.map((image) => image.repository)), [localImages]);
  const suggestedImages = imageResults.map((image) => {
    const fullName = image.repo_name.includes('/') ? `${image.repo_name}:latest` : `${image.repo_name}:latest`;
    const libraryName = image.repo_name.includes('/') ? fullName : `library/${fullName}`;
    const repositoryName = image.repo_name.includes('/') ? image.repo_name : image.repo_name;
    return {
      ...image,
      fullName,
      pulled: localImageNames.has(fullName) || localImageNames.has(libraryName) || localImageRepos.has(repositoryName),
    };
  });
  const localImageSuggestions = localImages
    .filter((image) => image.name)
    .slice(0, 16)
    .map((image) => ({
      repo_name: image.repository || image.name,
      fullName: image.name,
      short_description: `${image.repository || 'local'}:${image.tag || 'latest'} · ${image.size || '已拉取镜像'}`,
      is_official: false,
      pulled: true,
      star_count: 0,
    }));
  const showingLocalImages = imageQuery.trim().length === 0;
  const visibleImages = showingLocalImages ? localImageSuggestions : suggestedImages;

  const selectImage = (imageName) => {
    setImageQuery(imageName);
    setForm((current) => ({ ...current, image: imageName }));
    setImageSearchOpen(false);
  };
  const applyImportedCommand = () => {
    setImportError('');
    try {
      const parsed = parseDockerRunCommand(importCommand);
      setForm((current) => ({ ...current, ...parsed }));
      setImageQuery(parsed.image || '');
      setImageSearchOpen(false);
      setImportOpen(false);
      setStep(parsed.image ? 4 : 1);
      notify?.({ title: '导入命令成功', message: parsed.name || parsed.image });
    } catch (parseError) {
      setImportError(friendlyError(parseError.message));
    }
  };
  const addPort = () => setForm((current) => ({
    ...current,
    ports: [...current.ports, { id: makeEditId(), hostPort: '', containerPort: '', protocol: 'tcp' }],
  }));
  const updatePort = (id, field, value) => setForm((current) => ({
    ...current,
    ports: current.ports.map((port) => (port.id === id ? { ...port, [field]: value } : port)),
  }));
  const removePort = (id) => setForm((current) => ({ ...current, ports: current.ports.filter((port) => port.id !== id) }));
  const addEnv = () => setForm((current) => ({ ...current, env: [...current.env, { id: makeEditId(), key: '', value: '' }] }));
  const updateEnv = (id, field, value) => setForm((current) => ({ ...current, env: current.env.map((item) => (item.id === id ? { ...item, [field]: value } : item)) }));
  const removeEnv = (id) => setForm((current) => ({ ...current, env: current.env.filter((item) => item.id !== id) }));
  const addMount = () => setForm((current) => ({ ...current, mounts: [...current.mounts, { id: makeEditId(), type: 'bind', host: '', container: '', mode: 'rw' }] }));
  const updateMount = (id, field, value) => setForm((current) => ({ ...current, mounts: current.mounts.map((item) => (item.id === id ? { ...item, [field]: value } : item)) }));
  const removeMount = (id) => setForm((current) => ({ ...current, mounts: current.mounts.filter((item) => item.id !== id) }));

  const submit = async () => {
    setSaving(true);
    setError('');
    setCreateProgress({
      status: '连接创建任务',
      stage: 'prepare',
      progress: 2,
      logs: ['正在连接远端 Docker daemon'],
      done: false,
      error: false,
    });
    try {
      const socket = io(SOCKET_BASE_URL, {
        transports: ['websocket'],
        reconnectionAttempts: 2,
      });
      createSocketRef.current = socket;
      socket.on('connect', () => {
        socket.emit('container/create/open', {
          nodeId: node.id,
          config: buildCreatePayload(),
        });
      });
      socket.on('container/create/progress', (packet) => {
        const data = packet?.data || {};
        setCreateProgress((current) => {
          const line = [data.stage, data.layer, data.status].filter(Boolean).join(' · ');
          const logs = [...(current?.logs || []), line].filter(Boolean).slice(-8);
          return {
            status: data.status || current?.status || '创建中',
            stage: data.stage || current?.stage || 'create',
            progress: Math.max(Number(current?.progress || 0), Number(data.progress || 0)),
            layer: data.layer || '',
            layers: data.layers || current?.layers || 0,
            logs,
            done: false,
            error: false,
          };
        });
      });
      socket.on('container/create/done', (packet) => {
        const output = packet?.data?.output || '';
        setCreateProgress((current) => ({
          ...(current || {}),
          status: '创建完成',
          stage: 'done',
          progress: 100,
          logs: [...(current?.logs || []), output ? `容器 ID ${output.slice(0, 12)}` : '容器已创建'].slice(-8),
          done: true,
          error: false,
        }));
        notify?.({ title: '容器创建完成', message: form.name || form.image });
        onCreated?.();
        socket.disconnect();
        createSocketRef.current = null;
        setSaving(false);
      });
      socket.on('container/create/error', (packet) => {
        const message = friendlyError(packet?.message || '容器创建失败');
        setCreateProgress((current) => ({
          ...(current || {}),
          status: message,
          progress: current?.progress || 0,
          logs: [...(current?.logs || []), message].slice(-8),
          done: false,
          error: true,
        }));
        setError(message);
        notify?.({ type: 'error', title: '容器创建失败', message, duration: 5200 });
        socket.disconnect();
        createSocketRef.current = null;
        setSaving(false);
      });
      socket.on('connect_error', (socketError) => {
        const message = friendlyError(socketError.message);
        setCreateProgress((current) => ({
          ...(current || {}),
          status: message,
          logs: [...(current?.logs || []), message].slice(-8),
          error: true,
        }));
        notify?.({ type: 'error', title: '创建进度连接失败', message, duration: 5200 });
        setSaving(false);
      });
    } catch (createError) {
      const message = friendlyError(createError.message);
      setError(message);
      notify?.({ type: 'error', title: '容器创建失败', message, duration: 5200 });
      setSaving(false);
    }
  };

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        const locked = saving && !createProgress?.done && !createProgress?.error;
        if (event.target === event.currentTarget && !locked) onClose();
      }}
    >
      <section className={importOpen ? 'app-settings-modal create-container-modal import-open' : 'app-settings-modal create-container-modal'} role="dialog" aria-modal="true" aria-label="创建 Docker 容器">
        <div className="wizard-head">
          <div>
            <h2>创建 Docker 容器</h2>
            <span>{node.name}</span>
          </div>
          <div className="wizard-head-actions">
            <button className={importOpen ? 'import-command-head-button active' : 'import-command-head-button'} onClick={() => setImportOpen((value) => !value)} type="button">
              <TerminalSquare size={16} />
              导入命令
            </button>
            <button aria-label="关闭" className="settings-close" onClick={onClose} type="button"><X size={20} /></button>
          </div>
        </div>
        {importOpen && (
          <section className="import-command-panel head-import-panel open">
            <div className="import-command-content">
              <div>
                <strong>导入 Docker Run 命令</strong>
                <span>支持 --name、--restart、-p、-e、-v、--network、-w、--cpus、--memory、--privileged、-it 等常用参数。</span>
              </div>
              <textarea
                value={importCommand}
                onChange={(event) => setImportCommand(event.target.value)}
                placeholder="docker run -d --name mc -p 25565:25565 -v /root/mc:/root/mc -w /root/mc azul-zulu:21 java -jar server.jar"
              />
              {importError && <p>{importError}</p>}
              <div>
                <button className="secondary" onClick={() => setImportOpen(false)} type="button">收起</button>
                <button className="primary" onClick={applyImportedCommand} type="button">导入配置</button>
              </div>
            </div>
          </section>
        )}
        <div className="wizard-steps">
          {steps.map((item, index) => (
            <button className={index === step ? 'active' : index < step ? 'done' : ''} key={item} onClick={() => setStep(index)} type="button">
              <b>{index + 1}</b>{item}
            </button>
          ))}
        </div>
        <div className="wizard-body create-wizard-body">
          {createProgress && (
            <ContainerCreateProgress progress={createProgress} />
          )}
          {!createProgress && <>
          {step === 0 && (
            <div className="wizard-grid create-basic-grid">
              <SettingField title="容器名称" desc="Docker 容器名称，建议使用英文、数字和短横线。" required>
                <input value={form.name} onChange={(event) => setField('name', event.target.value)} placeholder="例如 web-nginx-01" />
              </SettingField>
              <SettingField title="重启策略" desc="宿主机重启或容器退出后的恢复方式。">
                <InlineSelect options={restartOptions} open={restartOpen} setOpen={setRestartOpen} selectRef={restartRef} value={form.restartPolicy} onChange={(value) => setField('restartPolicy', value)} />
              </SettingField>
            </div>
          )}
          {step === 1 && (
            <div className="wizard-grid single">
              <section className="docker-setting-section flat">
                <div className="section-title">
                  <h3>镜像</h3>
                  <span>输入镜像名会搜索 Docker Hub；空白时显示远端已拉取镜像。</span>
                </div>
                <div className="image-search-control" ref={imageSearchRef}>
                  <div className="image-search-box">
                    <Search size={18} />
                    <input
                      value={imageQuery}
                      onChange={(event) => {
                        setImageQuery(event.target.value);
                        setField('image', event.target.value);
                        setImageSearchOpen(true);
                      }}
                      onFocus={() => setImageSearchOpen(true)}
                      placeholder="搜索镜像，例如 nginx、mysql、itzg/minecraft-server"
                    />
                    {imageQuery && (
                      <button
                        aria-label="清空镜像搜索"
                        className="image-clear"
                        onClick={() => {
                          setImageQuery('');
                          setField('image', '');
                          setImageSearchOpen(true);
                        }}
                        type="button"
                      >
                        <X size={15} />
                      </button>
                    )}
                    {imageLoading && <span>搜索中</span>}
                  </div>
                  {imageSearchOpen && (showingLocalImages || imageQuery.trim().length >= 2) && (
                    <div className="image-result-list" onWheel={(event) => event.stopPropagation()}>
                      {showingLocalImages && <div className="image-result-title">已拉取镜像</div>}
                      {visibleImages.map((image) => (
                        <button className={form.image === image.fullName ? 'image-result active' : 'image-result'} key={image.repo_name} onClick={() => selectImage(image.fullName)} type="button">
                          <DockerImageIcon image={image.repo_name} />
                          <div>
                            <strong>{image.fullName}</strong>
                            <span>{image.short_description || 'Docker Hub repository'}</span>
                          </div>
                          <div className="image-tags">
                            {image.is_official && <b>官方</b>}
                            {image.pulled && <b className="pulled">已拉取</b>}
                            {!showingLocalImages && <em>{Number(image.star_count || 0).toLocaleString()} stars</em>}
                          </div>
                        </button>
                      ))}
                      {!imageLoading && visibleImages.length === 0 && (
                        <div className="image-empty">{showingLocalImages ? '远端暂无已拉取镜像' : '没有找到镜像结果'}</div>
                      )}
                    </div>
                  )}
                </div>
              </section>
              <section className="docker-setting-section startup-command-card create-startup-command-card">
                <div className="section-title">
                  <h3>启动命令</h3>
                  <span>设置工作目录并覆盖镜像默认 CMD，留空则使用镜像默认配置。</span>
                </div>
                <input className="workdir-input" value={form.workingDir} onChange={(event) => setField('workingDir', event.target.value)} placeholder="工作目录，例如 /app，可留空" />
                <textarea className="command-editor" value={form.command} onChange={(event) => setField('command', event.target.value)} placeholder="例如: npm start 或 java -jar server.jar" />
              </section>
            </div>
          )}
          {step === 2 && (
            <div className="wizard-grid create-network-grid">
              <SettingField title="网络模式" desc="常用 bridge，host 会直接使用宿主机网络。">
                <InlineSelect options={networkOptions} open={networkOpen} setOpen={setNetworkOpen} selectRef={networkRef} value={form.networkMode} onChange={(value) => setField('networkMode', value)} />
              </SettingField>
              <section className="docker-setting-section flat">
                <div className="section-title">
                  <h3>端口映射</h3>
                  <span>host 模式会忽略端口映射。</span>
                </div>
                <div className="editable-port-list">
                  {form.ports.map((port) => (
                    <div className="editable-port-card" key={port.id}>
                      <input inputMode="numeric" placeholder="宿主端口" value={port.hostPort} onChange={(event) => updatePort(port.id, 'hostPort', event.target.value.replace(/[^\d-]/g, ''))} />
                      <i>→</i>
                      <input inputMode="numeric" placeholder="容器端口" value={port.containerPort} onChange={(event) => updatePort(port.id, 'containerPort', event.target.value.replace(/[^\d-]/g, ''))} />
                      <ProtocolToggle value={port.protocol} onChange={(value) => updatePort(port.id, 'protocol', value)} />
                      <button aria-label="删除端口映射" onClick={() => removePort(port.id)} type="button"><X size={14} /></button>
                    </div>
                  ))}
                  {form.ports.length === 0 && <div className="port-empty-line">暂无端口映射</div>}
                  <button className="add-port-button" onClick={addPort} type="button"><Plus size={15} />添加端口映射</button>
                </div>
              </section>
            </div>
          )}
          {step === 3 && (
            <div className="wizard-grid create-advanced-grid">
              <section className="docker-setting-section flat collapsible-setting">
                <CollapsibleSectionTitle
                  count={editRowsToEnvSpecs(form.env).length}
                  desc="以 KEY=VALUE 形式注入容器。"
                  open={envOpen}
                  setOpen={setEnvOpen}
                  title="环境变量"
                />
                {envOpen && <div className="editable-config-list">
                  {form.env.map((item) => (
                    <div className="editable-kv-card" key={item.id}>
                      <input placeholder="变量名" value={item.key} onChange={(event) => updateEnv(item.id, 'key', event.target.value)} />
                      <i>=</i>
                      <input placeholder="变量值" value={item.value} onChange={(event) => updateEnv(item.id, 'value', event.target.value)} />
                      <button aria-label="删除环境变量" onClick={() => removeEnv(item.id)} type="button"><X size={14} /></button>
                    </div>
                  ))}
                  {form.env.length === 0 && <div className="port-empty-line">暂无环境变量</div>}
                  <button className="add-port-button" onClick={addEnv} type="button"><Plus size={15} />添加环境变量</button>
                </div>}
              </section>
              <section className="docker-setting-section flat collapsible-setting">
                <CollapsibleSectionTitle
                  count={editRowsToMountSpecs(form.mounts).length}
                  desc="支持宿主路径与 Docker 卷。"
                  open={mountsOpen}
                  setOpen={setMountsOpen}
                  title="挂载点"
                />
                {mountsOpen && <div className="editable-config-list">
                  {form.mounts.map((item) => (
                    <div className="editable-mount-card" key={item.id}>
                      <div className="mount-source-row">
                        <ProtocolToggle labels={{ left: '路径', right: '卷' }} options={['bind', 'volume']} value={item.type || 'bind'} onChange={(value) => updateMount(item.id, 'type', value)} />
                        <input placeholder={item.type === 'volume' ? 'volume_name' : '/host/path'} value={item.host} onChange={(event) => updateMount(item.id, 'host', event.target.value)} />
                      </div>
                      <div className="mount-target-row">
                        <i>→</i>
                        <input placeholder="/container/path" value={item.container} onChange={(event) => updateMount(item.id, 'container', event.target.value)} />
                        <ProtocolToggle labels={{ left: 'RW', right: 'RO' }} options={['rw', 'ro']} value={item.mode} onChange={(value) => updateMount(item.id, 'mode', value)} />
                        <button aria-label="删除挂载点" onClick={() => removeMount(item.id)} type="button"><X size={14} /></button>
                      </div>
                    </div>
                  ))}
                  {form.mounts.length === 0 && <div className="port-empty-line">暂无挂载点</div>}
                  <button className="add-port-button" onClick={addMount} type="button"><Plus size={15} />添加挂载点</button>
                </div>}
              </section>
              <section className="resource-setting-panel collapsible-setting">
                <CollapsibleSectionTitle
                  count={resourceCount}
                  desc="CPU、内存和网络限速；留空表示不限制。"
                  open={resourceOpen}
                  setOpen={setResourceOpen}
                  title="资源限制"
                />
                {resourceOpen && <div className="resource-grid">
                  <SettingField title="CPU" desc="核心数">
                    <input inputMode="decimal" value={form.cpuLimit} onChange={(event) => setField('cpuLimit', event.target.value.replace(/[^\d.]/g, ''))} placeholder="不限制" />
                  </SettingField>
                  <SettingField title="内存" desc="512m / 2g">
                    <input value={form.memoryLimit} onChange={(event) => setField('memoryLimit', event.target.value.toLowerCase().replace(/[^0-9.mg]/g, ''))} placeholder="不限制" />
                  </SettingField>
                  <SettingField title="下载限速" desc="配置标签">
                    <input value={form.networkDownloadLimit} onChange={(event) => setField('networkDownloadLimit', event.target.value)} placeholder="未设置" />
                  </SettingField>
                  <SettingField title="上传限速" desc="配置标签">
                    <input value={form.networkUploadLimit} onChange={(event) => setField('networkUploadLimit', event.target.value)} placeholder="未设置" />
                  </SettingField>
                </div>}
              </section>
              <div className="create-toggle-grid">
                <div className="setting-toggle-row">
                  <div>
                    <strong>特权模式</strong>
                    <span>允许容器获得更高系统权限。</span>
                  </div>
                  <button aria-pressed={form.privileged} className={form.privileged ? 'switch-control checked' : 'switch-control'} onClick={() => setField('privileged', !form.privileged)} type="button"><b></b></button>
                </div>
                <div className="setting-toggle-row">
                  <div>
                    <strong>进程终端</strong>
                    <span>默认开启 stdin 与 tty。</span>
                  </div>
                  <button
                    aria-pressed={form.stdinOpen && form.tty}
                    className={form.stdinOpen && form.tty ? 'switch-control checked' : 'switch-control'}
                    onClick={() => {
                      const next = !(form.stdinOpen && form.tty);
                      setForm((current) => ({ ...current, stdinOpen: next, tty: next }));
                    }}
                    type="button"
                  >
                    <b></b>
                  </button>
                </div>
              </div>
            </div>
          )}
          {step === 4 && (
            <div className="wizard-review">
              <div className="review-main">
                <section className="review-hero">
                  <DockerImageIcon image={form.image} />
                  <div>
                    <span>即将创建 Docker 容器</span>
                    <h3>{form.name || '未命名容器'}</h3>
                    <p>{form.image || '-'}</p>
                  </div>
                </section>
                <section className="review-detail-grid">
                  <ReviewCard title="端口映射" value={`${editRowsToPortSpecs(form.ports).length} 项`} detail={editRowsToPortSpecs(form.ports).join('，') || '未配置端口映射'} />
                  <ReviewCard title="环境变量" value={`${editRowsToEnvSpecs(form.env).length} 项`} detail={editRowsToEnvSpecs(form.env).map((item) => item.split('=')[0]).join('，') || '未配置环境变量'} />
                  <ReviewCard title="挂载点" value={`${editRowsToMountSpecs(form.mounts).length} 项`} detail={editRowsToMountSpecs(form.mounts).join('，') || '未配置挂载点'} />
                  <ReviewCard title="资源限制" value={resourceCount ? `${resourceCount} 项` : '不限制'} detail={[form.cpuLimit ? `CPU ${form.cpuLimit}` : '', form.memoryLimit ? `内存 ${form.memoryLimit}` : '', form.networkDownloadLimit ? `下载 ${form.networkDownloadLimit}` : '', form.networkUploadLimit ? `上传 ${form.networkUploadLimit}` : ''].filter(Boolean).join('，') || '不限制 CPU、内存和网络'} />
                </section>
              </div>
              <aside className="review-side">
                <section className="review-summary-panel">
                  <div className="review-panel-title">
                    <h3>创建清单</h3>
                    <span>确认后将拉取镜像并启动容器。</span>
                  </div>
                  <div className="review-summary-list">
                    <ReviewRow label="节点" value={node.name} />
                    <ReviewRow label="网络模式" value={form.networkMode} />
                    <ReviewRow label="重启策略" value={formatRestartPolicy(form.restartPolicy)} />
                    <ReviewRow label="工作目录" value={form.workingDir || '默认'} />
                    <ReviewRow label="进程终端" value={form.stdinOpen && form.tty ? '开启' : '关闭'} />
                  </div>
                </section>
                <div className="review-notice">
                  <CheckCircle2 size={18} />
                  <span>创建过程会展示镜像拉取和容器启动进度，完成后自动刷新列表。</span>
                </div>
              </aside>
            </div>
          )}
          </>}
        </div>
        <div className="wizard-actions">
          <button className="secondary" disabled={saving && !createProgress?.done && !createProgress?.error} onClick={onClose} type="button">{createProgress?.done ? '关闭' : '取消'}</button>
          {!createProgress && <button className="secondary" disabled={step === 0} onClick={() => setStep((value) => Math.max(0, value - 1))} type="button">上一步</button>}
          {!createProgress && (step < steps.length - 1 ? (
            <button className="primary" disabled={!canNext} onClick={() => setStep((value) => Math.min(steps.length - 1, value + 1))} type="button">下一步</button>
          ) : (
            <button className="primary" disabled={saving || !form.image.trim()} onClick={submit} type="button">{saving ? '创建中...' : '创建容器'}</button>
          ))}
        </div>
      </section>
    </div>
  );
}

function ConsoleMetricCard({ icon: Icon, label, value, tone, compact = false }) {
  const length = String(value).length;
  const valueClass = length > 26 ? 'tiny' : length > 21 ? 'mini' : length > 18 ? 'long' : length > 12 ? 'medium' : '';
  const displayValue = Array.isArray(value)
    ? value.map((item) => <span className="metric-value-line" key={item}>{item}</span>)
    : value;
  const arrayClass = Array.isArray(value) ? 'multi-value' : '';
  return (
    <article className={['console-metric-card', tone, valueClass ? `value-${valueClass}` : '', arrayClass, compact ? 'compact' : ''].filter(Boolean).join(' ')}>
      <span><Icon size={18} /></span>
      <div>
        <em>{label}</em>
        <strong className={Array.isArray(value) ? 'inline-values' : valueClass}>{displayValue}</strong>
      </div>
    </article>
  );
}

function ContainerCreateProgress({ progress }) {
  const percent = Math.min(Math.max(Number(progress?.progress || 0), 0), 100);
  const steps = [
    { key: 'prepare', label: '准备' },
    { key: 'pull', label: '拉取镜像' },
    { key: 'create', label: '创建容器' },
    { key: 'done', label: '完成' },
  ];
  const activeIndex = progress?.stage === 'created' ? 2 : steps.findIndex((item) => item.key === progress?.stage);
  return (
    <section className={progress?.error ? 'create-progress-card error' : 'create-progress-card'}>
      <div className="create-progress-head">
        <div>
          <em>{progress?.error ? 'TASK FAILED' : progress?.done ? 'TASK COMPLETED' : 'TASK RUNNING'}</em>
          <strong>{progress?.error ? '创建失败' : progress?.done ? '创建完成' : '正在创建容器'}</strong>
          <span>{progress?.status || '准备中'}</span>
        </div>
        <b>{Math.round(percent)}%</b>
      </div>
      <div className="create-progress-bar"><i style={{ width: `${percent}%` }}></i></div>
      <div className="create-progress-steps">
        {steps.map((item, index) => (
          <span className={index < activeIndex || progress?.done ? 'done' : index === activeIndex ? 'active' : ''} key={item.key}>
            {index < activeIndex || progress?.done ? <Check size={12} /> : <b>{index + 1}</b>}
            {item.label}
          </span>
        ))}
      </div>
      <div className="create-progress-log">
        <div className="create-progress-log-head">
          <span>执行日志</span>
          <strong>{progress?.logs?.length || 0} 条</strong>
        </div>
        {(progress?.logs || []).map((line, index) => (
          <code key={`${line}-${index}`}>{line}</code>
        ))}
      </div>
    </section>
  );
}

function ContainerEditModal({ container, node, notify, onClose, onSaved, onDeleted }) {
  useBodyScrollLock();
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [error, setError] = useState('');
  const [imageQuery, setImageQuery] = useState(container.image || '');
  const [imageResults, setImageResults] = useState([]);
  const [localImages, setLocalImages] = useState([]);
  const [imageLoading, setImageLoading] = useState(false);
  const [imageSearchOpen, setImageSearchOpen] = useState(false);
  const [restartOpen, setRestartOpen] = useState(false);
  const [networkOpen, setNetworkOpen] = useState(false);
  const [envOpen, setEnvOpen] = useState(false);
  const [mountsOpen, setMountsOpen] = useState(false);
  const [resourceOpen, setResourceOpen] = useState(false);
  const imageSearchRef = useRef(null);
  const restartRef = useRef(null);
  const networkRef = useRef(null);
  const [form, setForm] = useState({
    name: container.name,
    image: container.image || '',
    restartPolicy: container.restartPolicy || 'no',
    networkMode: container.network?.mode || 'bridge',
    ports: portsToEditRows(container.network?.ports),
    env: keyValueStringsToRows(container.config?.env),
    mounts: mountStringsToRows(container.config?.mounts),
    privileged: Boolean(container.config?.privileged),
    workingDir: container.config?.workingDir || '',
    command: container.config?.command || '',
    cpuLimit: container.config?.cpuLimit || '',
    memoryLimit: bytesToMemoryInput(container.config?.memoryLimit),
    networkDownloadLimit: container.config?.networkDownloadLimit || '',
    networkUploadLimit: container.config?.networkUploadLimit || '',
    stdinOpen: container.config?.stdinOpen === false ? false : true,
    tty: container.config?.tty === false ? false : true,
  });
  const restartOptions = [
    { value: 'no', label: 'No' },
    { value: 'always', label: 'Always' },
    { value: 'unless-stopped', label: 'Unless stopped' },
    { value: 'on-failure', label: 'On failure' },
  ];
  const networkOptions = [
    { value: 'bridge', label: 'bridge' },
    { value: 'host', label: 'host' },
    { value: 'none', label: 'none' },
  ];

  useEffect(() => {
    setImageQuery(container.image || '');
    setForm({
      name: container.name,
      image: container.image || '',
      restartPolicy: container.restartPolicy || 'no',
      networkMode: container.network?.mode || 'bridge',
      ports: portsToEditRows(container.network?.ports),
      env: keyValueStringsToRows(container.config?.env),
      mounts: mountStringsToRows(container.config?.mounts),
      privileged: Boolean(container.config?.privileged),
      workingDir: container.config?.workingDir || '',
      command: container.config?.command || '',
      cpuLimit: container.config?.cpuLimit || '',
      memoryLimit: bytesToMemoryInput(container.config?.memoryLimit),
      networkDownloadLimit: container.config?.networkDownloadLimit || '',
      networkUploadLimit: container.config?.networkUploadLimit || '',
      stdinOpen: container.config?.stdinOpen === false ? false : true,
      tty: container.config?.tty === false ? false : true,
    });
  }, [container.id]);

  useEffect(() => {
    let ignore = false;
    fetchImages(node.id)
      .then((items) => {
        if (!ignore) setLocalImages(items || []);
      })
      .catch(() => {
        if (!ignore) setLocalImages([]);
      });
    return () => {
      ignore = true;
    };
  }, [node.id]);

  useEffect(() => {
    const keyword = imageQuery.trim();
    if (keyword.length < 2) {
      setImageResults([]);
      return undefined;
    }
    let ignore = false;
    const timer = setTimeout(async () => {
      setImageLoading(true);
      try {
        const results = await searchDockerImages(keyword);
        if (!ignore) setImageResults(results || []);
      } catch {
        if (!ignore) setImageResults([]);
      } finally {
        if (!ignore) setImageLoading(false);
      }
    }, 260);
    return () => {
      ignore = true;
      clearTimeout(timer);
    };
  }, [imageQuery]);

  useEffect(() => {
    const closePopovers = (event) => {
      if (imageSearchRef.current && !imageSearchRef.current.contains(event.target)) {
        setImageSearchOpen(false);
      }
      if (restartRef.current && !restartRef.current.contains(event.target)) {
        setRestartOpen(false);
      }
      if (networkRef.current && !networkRef.current.contains(event.target)) {
        setNetworkOpen(false);
      }
    };
    document.addEventListener('mousedown', closePopovers);
    return () => document.removeEventListener('mousedown', closePopovers);
  }, []);

  const localImageNames = useMemo(() => new Set(localImages.map((image) => image.name)), [localImages]);
  const localImageRepos = useMemo(() => new Set(localImages.map((image) => image.repository)), [localImages]);
  const suggestedImages = imageResults.map((image) => {
    const fullName = image.repo_name.includes('/') ? `${image.repo_name}:latest` : `${image.repo_name}:latest`;
    const libraryName = image.repo_name.includes('/') ? fullName : `library/${fullName}`;
    const repositoryName = image.repo_name.includes('/') ? image.repo_name : image.repo_name;
    return {
      ...image,
      fullName,
      pulled: localImageNames.has(fullName) || localImageNames.has(libraryName) || localImageRepos.has(repositoryName),
    };
  });
  const localImageSuggestions = localImages
    .filter((image) => image.name)
    .slice(0, 16)
    .map((image) => ({
      repo_name: image.repository || image.name,
      fullName: image.name,
      short_description: `${image.repository || 'local'}:${image.tag || 'latest'} · ${image.size || '已拉取镜像'}`,
      is_official: false,
      pulled: true,
      star_count: 0,
    }));
  const showingLocalImages = imageQuery.trim().length === 0;
  const visibleImages = showingLocalImages ? localImageSuggestions : suggestedImages;
  const resourceCount = [form.cpuLimit, form.memoryLimit, form.networkDownloadLimit, form.networkUploadLimit].filter(Boolean).length;

  const selectImage = (imageName) => {
    setImageQuery(imageName);
    setForm((current) => ({ ...current, image: imageName }));
    setImageSearchOpen(false);
  };
  const addPort = () => {
    setForm((current) => ({
      ...current,
      ports: [...current.ports, { id: makeEditId(), hostPort: '', containerPort: '', protocol: 'tcp' }],
    }));
  };
  const updatePort = (id, field, value) => {
    setForm((current) => ({
      ...current,
      ports: current.ports.map((port) => (port.id === id ? { ...port, [field]: value } : port)),
    }));
  };
  const removePort = (id) => {
    setForm((current) => ({ ...current, ports: current.ports.filter((port) => port.id !== id) }));
  };
  const addEnv = () => {
    setForm((current) => ({ ...current, env: [...current.env, { id: makeEditId(), key: '', value: '' }] }));
  };
  const updateEnv = (id, field, value) => {
    setForm((current) => ({ ...current, env: current.env.map((item) => (item.id === id ? { ...item, [field]: value } : item)) }));
  };
  const removeEnv = (id) => {
    setForm((current) => ({ ...current, env: current.env.filter((item) => item.id !== id) }));
  };
  const addMount = () => {
    setForm((current) => ({ ...current, mounts: [...current.mounts, { id: makeEditId(), type: 'bind', host: '', container: '', mode: 'rw' }] }));
  };
  const updateMount = (id, field, value) => {
    setForm((current) => ({ ...current, mounts: current.mounts.map((item) => (item.id === id ? { ...item, [field]: value } : item)) }));
  };
  const removeMount = (id) => {
    setForm((current) => ({ ...current, mounts: current.mounts.filter((item) => item.id !== id) }));
  };

  const save = async () => {
    if (saving || deleting) return;
    setSaving(true);
    setError('');
    try {
      await updateContainer(node.id, container.id, {
        ...form,
        ports: editRowsToPortSpecs(form.ports),
        env: editRowsToEnvSpecs(form.env),
        mounts: editRowsToMountSpecs(form.mounts),
      });
      const latestState = await waitForContainerState(
        node.id,
        { id: container.id, name: form.name, fallbackName: container.name },
        (item) => Boolean(item && CONTAINER_FINAL_STATES.has(item.state || '')),
        { timeout: 24000, timeoutMessage: '配置已提交，但容器状态确认超时' },
      );
      let latest = latestState;
      try {
        latest = await fetchContainer(node.id, latestState?.id || container.id, { fast: true });
      } catch {
        // The state-confirmed snapshot is still newer than the modal's original props.
      }
      notify?.({ title: '容器配置已保存', message: form.name });
      await onSaved?.(form.name, latest);
    } catch (saveError) {
      const message = friendlyError(saveError.message);
      setError(message);
      notify?.({ type: 'error', title: '容器配置保存失败', message, duration: 4600 });
    } finally {
      setSaving(false);
    }
  };

  const removeContainer = async () => {
    if (deleting || saving) return;
    setDeleting(true);
    setError('');
    try {
      await deleteContainer(node.id, container.id);
      await waitForContainerState(
        node.id,
        { id: container.id, name: container.name },
        (_item, items) => !items.some((item) => sameContainerIdentity(item, { id: container.id, name: container.name })),
        { timeout: 16000, timeoutMessage: '删除已提交，但容器仍在列表中' },
      );
      notify?.({ title: '容器已删除', message: container.name });
      onDeleted?.();
    } catch (deleteError) {
      const message = friendlyError(deleteError.message);
      setError(message);
      notify?.({ type: 'error', title: '容器删除失败', message, duration: 5200 });
    } finally {
      setDeleting(false);
      setConfirmDelete(false);
    }
  };

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !saving && !deleting) onClose();
      }}
    >
      <section className={saving || deleting ? 'app-settings-modal is-busy' : 'app-settings-modal'} role="dialog" aria-modal="true" aria-label="编辑容器">
        <div className="settings-head">
          <div>
            <h2>Docker 容器设置</h2>
            <span>{container.name}</span>
          </div>
          <button aria-label="关闭" className="settings-close" disabled={saving || deleting} onClick={onClose} type="button"><X size={20} /></button>
        </div>

        <div className="docker-settings-body">
          <div className="docker-settings-main">
            <section className="docker-setting-section">
              <div className="section-title">
                <h3>镜像</h3>
                <span>搜索 Docker Hub 或选择远端已拉取镜像。</span>
              </div>
              <div className="image-search-control" ref={imageSearchRef}>
                <div className="image-search-box">
                  <Search size={18} />
                  <input
                    value={imageQuery}
                    onChange={(event) => {
                      setImageQuery(event.target.value);
                      setForm((current) => ({ ...current, image: event.target.value }));
                      setImageSearchOpen(true);
                    }}
                    onFocus={() => setImageSearchOpen(true)}
                    placeholder="搜索镜像，例如 nginx、mysql、itzg/minecraft-server"
                  />
                  {imageQuery && (
                    <button
                      aria-label="清空镜像搜索"
                      className="image-clear"
                      onClick={() => {
                        setImageQuery('');
                        setForm((current) => ({ ...current, image: '' }));
                        setImageSearchOpen(true);
                      }}
                      type="button"
                    >
                      <X size={15} />
                    </button>
                  )}
                  {imageLoading && <span>搜索中</span>}
                </div>
                {imageSearchOpen && (showingLocalImages || imageQuery.trim().length >= 2) && (
                  <div className="image-result-list" onWheel={(event) => event.stopPropagation()}>
                    {showingLocalImages && <div className="image-result-title">已拉取镜像</div>}
                    {visibleImages.map((image) => (
                      <button className={form.image === image.fullName ? 'image-result active' : 'image-result'} key={image.repo_name} onClick={() => selectImage(image.fullName)} type="button">
                        <DockerImageIcon image={image.repo_name} />
                        <div>
                          <strong>{image.fullName}</strong>
                          <span>{image.short_description || 'Docker Hub repository'}</span>
                        </div>
                        <div className="image-tags">
                          {image.is_official && <b>官方</b>}
                          {image.pulled && <b className="pulled">已拉取</b>}
                          {!showingLocalImages && <em>{Number(image.star_count || 0).toLocaleString()} stars</em>}
                        </div>
                      </button>
                    ))}
                    {!imageLoading && visibleImages.length === 0 && (
                      <div className="image-empty">{showingLocalImages ? '远端暂无已拉取镜像' : '没有找到镜像结果'}</div>
                    )}
                  </div>
                )}
              </div>
            </section>

            <section className="docker-setting-section">
              <div className="section-title">
                <h3>端口映射</h3>
                <span>添加、删除或修改端口后，保存会自动重建容器并生效。</span>
              </div>
              <div className="editable-port-list">
                {form.ports.map((port) => (
                  <div className="editable-port-card" key={port.id}>
                    <input
                      inputMode="numeric"
                      placeholder="宿主端口"
                      value={port.hostPort}
                      onChange={(event) => updatePort(port.id, 'hostPort', event.target.value.replace(/[^\d-]/g, ''))}
                    />
                    <i>→</i>
                    <input
                      inputMode="numeric"
                      placeholder="容器端口"
                      value={port.containerPort}
                      onChange={(event) => updatePort(port.id, 'containerPort', event.target.value.replace(/[^\d-]/g, ''))}
                    />
                    <ProtocolToggle
                      value={port.protocol}
                      onChange={(value) => updatePort(port.id, 'protocol', value)}
                    />
                    <button aria-label="删除端口映射" onClick={() => removePort(port.id)} type="button"><X size={14} /></button>
                  </div>
                ))}
              {form.ports.length === 0 && <div className="port-empty-line">暂无端口映射</div>}
                <button className="add-port-button" onClick={addPort} type="button"><Plus size={15} />添加端口映射</button>
              </div>
            </section>

            <section className="docker-setting-section startup-command-card">
              <div className="section-title">
                <h3>启动命令</h3>
                <span>设置工作目录并覆盖镜像默认 CMD，留空则使用镜像默认配置。</span>
              </div>
              <input
                className="workdir-input"
                value={form.workingDir}
                onChange={(event) => setForm((current) => ({ ...current, workingDir: event.target.value }))}
                placeholder="工作目录，例如 /app，可留空"
              />
              <textarea
                className="command-editor"
                value={form.command}
                onChange={(event) => setForm((current) => ({ ...current, command: event.target.value }))}
                placeholder="例如: npm start 或 java -jar server.jar"
              />
            </section>

            <section className="docker-setting-section collapsible-setting">
              <CollapsibleSectionTitle
                count={editRowsToEnvSpecs(form.env).length}
                desc="以 KEY=VALUE 形式注入容器，保存后自动重建生效。"
                open={envOpen}
                setOpen={setEnvOpen}
                title="环境变量"
              />
              {envOpen && <div className="editable-config-list">
                {form.env.map((item) => (
                  <div className="editable-kv-card" key={item.id}>
                    <input placeholder="变量名" value={item.key} onChange={(event) => updateEnv(item.id, 'key', event.target.value)} />
                    <i>=</i>
                    <input placeholder="变量值" value={item.value} onChange={(event) => updateEnv(item.id, 'value', event.target.value)} />
                    <button aria-label="删除环境变量" onClick={() => removeEnv(item.id)} type="button"><X size={14} /></button>
                  </div>
                ))}
                {form.env.length === 0 && <div className="port-empty-line">暂无环境变量</div>}
                <button className="add-port-button" onClick={addEnv} type="button"><Plus size={15} />添加环境变量</button>
              </div>}
            </section>

            <section className="docker-setting-section collapsible-setting">
              <CollapsibleSectionTitle
                count={editRowsToMountSpecs(form.mounts).length}
                desc="配置宿主机路径与容器路径映射，适合持久化数据。"
                open={mountsOpen}
                setOpen={setMountsOpen}
                title="挂载点"
              />
              {mountsOpen && <div className="editable-config-list">
                {form.mounts.map((item) => (
                  <div className="editable-mount-card" key={item.id}>
                    <div className="mount-source-row">
                      <ProtocolToggle
                        labels={{ left: '路径', right: '卷' }}
                        options={['bind', 'volume']}
                        value={item.type || 'bind'}
                        onChange={(value) => updateMount(item.id, 'type', value)}
                      />
                      <input placeholder={item.type === 'volume' ? 'volume_name' : '/host/path'} value={item.host} onChange={(event) => updateMount(item.id, 'host', event.target.value)} />
                    </div>
                    <div className="mount-target-row">
                      <i>→</i>
                      <input placeholder="/container/path" value={item.container} onChange={(event) => updateMount(item.id, 'container', event.target.value)} />
                      <ProtocolToggle
                        labels={{ left: 'RW', right: 'RO' }}
                        options={['rw', 'ro']}
                        value={item.mode}
                        onChange={(value) => updateMount(item.id, 'mode', value)}
                      />
                      <button aria-label="删除挂载点" onClick={() => removeMount(item.id)} type="button"><X size={14} /></button>
                    </div>
                  </div>
                ))}
                {form.mounts.length === 0 && <div className="port-empty-line">暂无挂载点</div>}
                <button className="add-port-button" onClick={addMount} type="button"><Plus size={15} />添加挂载点</button>
              </div>}
            </section>
          </div>

          <aside className="docker-settings-side">
            <SettingField title="容器名称" desc="保存后会立即执行 Docker rename。">
              <input value={form.name} onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))} />
            </SettingField>
            <SettingField title="重启策略" desc="控制容器退出后的恢复行为。">
              <InlineSelect
                options={restartOptions}
                open={restartOpen}
                setOpen={setRestartOpen}
                selectRef={restartRef}
                value={form.restartPolicy}
                onChange={(value) => setForm((current) => ({ ...current, restartPolicy: value }))}
              />
            </SettingField>
            <SettingField title="网络模式" desc="保存后会按所选网络模式自动生效。">
              <InlineSelect
                options={networkOptions}
                open={networkOpen}
                setOpen={setNetworkOpen}
                selectRef={networkRef}
                value={form.networkMode}
                onChange={(value) => setForm((current) => ({ ...current, networkMode: value }))}
              />
            </SettingField>
            <section className="resource-setting-panel collapsible-setting">
              <CollapsibleSectionTitle
                count={resourceCount}
                desc="CPU、内存和网络限速；留空表示不限制。"
                open={resourceOpen}
                setOpen={setResourceOpen}
                title="资源限制"
              />
              {resourceOpen && <div className="resource-grid">
                <SettingField title="CPU" desc="核心数，例如 1.5">
                  <input
                    inputMode="decimal"
                    value={form.cpuLimit}
                    onChange={(event) => setForm((current) => ({ ...current, cpuLimit: event.target.value.replace(/[^\d.]/g, '') }))}
                    placeholder="不限制"
                  />
                </SettingField>
                <SettingField title="内存" desc="例如 512m / 2g">
                  <input
                    value={form.memoryLimit}
                    onChange={(event) => setForm((current) => ({ ...current, memoryLimit: event.target.value.toLowerCase().replace(/[^0-9.mg]/g, '') }))}
                    placeholder="不限制"
                  />
                </SettingField>
                <SettingField title="下载限速" desc="例如 10mbit">
                  <input
                    value={form.networkDownloadLimit}
                    onChange={(event) => setForm((current) => ({ ...current, networkDownloadLimit: event.target.value }))}
                    placeholder="未设置"
                  />
                </SettingField>
                <SettingField title="上传限速" desc="例如 5mbit">
                  <input
                    value={form.networkUploadLimit}
                    onChange={(event) => setForm((current) => ({ ...current, networkUploadLimit: event.target.value }))}
                    placeholder="未设置"
                  />
                </SettingField>
              </div>}
              {resourceOpen && <div className="resource-note">网络限速当前会保存为北冥配置标签，强制限速需要宿主机 tc 执行层。</div>}
            </section>
            <div className="setting-toggle-row">
              <div>
                <strong>特权模式</strong>
                <span>启用后容器会获得更高系统权限。</span>
              </div>
              <button
                aria-pressed={form.privileged}
                className={form.privileged ? 'switch-control checked' : 'switch-control'}
                onClick={() => setForm((current) => ({ ...current, privileged: !current.privileged }))}
                type="button"
              >
                <b></b>
              </button>
            </div>
            <div className="setting-toggle-row">
              <div>
                <strong>进程终端</strong>
                <span>启用 stdin 与 tty，保存后重建容器。</span>
              </div>
              <button
                aria-pressed={form.stdinOpen && form.tty}
                className={form.stdinOpen && form.tty ? 'switch-control checked' : 'switch-control'}
                onClick={() => setForm((current) => {
                  const next = !(current.stdinOpen && current.tty);
                  return { ...current, stdinOpen: next, tty: next };
                })}
                type="button"
              >
                <b></b>
              </button>
            </div>
            <section className="danger-setting-panel">
              <div>
                <strong>删除容器</strong>
                <span>会强制停止并移除当前 Docker 容器，镜像与挂载卷不会自动删除。</span>
              </div>
              <button className="danger-secondary" disabled={deleting || saving} onClick={() => setConfirmDelete(true)} type="button">
                {deleting ? '删除中...' : '删除容器'}
              </button>
            </section>
          </aside>
          </div>

        <div className="settings-actions">
          <button className="secondary" disabled={saving || deleting} onClick={onClose} type="button">取消</button>
          <button className="primary" disabled={saving || deleting} onClick={save} type="button">{saving ? '保存并确认中...' : '保存配置'}</button>
        </div>
      </section>
      {confirmDelete && (
        <ConfirmDialog
          desc="删除后该容器会从远端 Docker 中移除，当前运行进程会被强制停止。镜像、数据卷和宿主机目录不会自动删除。"
          title={`删除容器「${container.name}」`}
          onCancel={() => setConfirmDelete(false)}
          onConfirm={removeContainer}
        />
      )}
    </div>
  );
}

function SettingField({ title, desc, required = false, children }) {
  return (
    <label className="setting-field">
      <strong>{required && <i>*</i>}{title}</strong>
      <span>{desc}</span>
      {children}
    </label>
  );
}

function CollapsibleSectionTitle({ title, desc, count, open, setOpen }) {
  return (
    <button className="section-title collapsible-title" onClick={() => setOpen(!open)} type="button">
      <div>
        <h3>{title}</h3>
        <span>{desc}</span>
      </div>
      <div className="collapse-meta">
        <b>{count} 项</b>
        <ChevronDown className={open ? 'open' : ''} size={16} />
      </div>
    </button>
  );
}

function InlineSelect({ options, value, onChange, open, setOpen, selectRef, className = '' }) {
  const active = options.find((option) => option.value === value) || options[0];
  return (
    <div className={['policy-select', className].filter(Boolean).join(' ')} ref={selectRef}>
      <button className={open ? 'policy-trigger open' : 'policy-trigger'} onClick={() => setOpen(!open)} type="button">
        <span>{active?.label || value}</span>
        <ChevronDown size={16} />
      </button>
      {open && (
        <div className="policy-menu">
          {options.map((option) => (
            <button
              className={option.value === value ? 'active' : ''}
              key={option.value}
              onClick={() => {
                onChange(option.value);
                setOpen(false);
              }}
              type="button"
            >
              <span>{option.label}</span>
              {option.value === value && <Check size={14} />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function ProtocolToggle({ value, onChange, options = ['tcp', 'udp'], labels = { left: 'TCP', right: 'UDP' } }) {
  const activeIndex = value === options[1] ? 1 : 0;
  const displayLabels = [labels.left, labels.right];
  return (
    <div className="protocol-toggle" role="group">
      <span className="protocol-thumb" style={{ transform: `translateX(${activeIndex * 100}%)` }}></span>
      {options.map((item, index) => (
        <button
          className={value === item ? 'active' : ''}
          key={item}
          onClick={() => onChange(item)}
          type="button"
        >
          {displayLabels[index] || item.toUpperCase()}
        </button>
      ))}
    </div>
  );
}

function ToggleSwitch({ checked }) {
  return (
    <span className={checked ? 'toggle-switch checked' : 'toggle-switch'}>
      <Check size={14} />
      <b></b>
    </span>
  );
}

function ContainerTerminal({ container, node, title }) {
  const terminalRef = useRef(null);
  const terminalWrapperRef = useRef(null);
  const terminalInputRef = useRef(null);
  const terminalInstanceRef = useRef(null);
  const fitAddonRef = useRef(null);
  const socketRef = useRef(null);
  const commandHistoryRef = useRef([]);
  const historyIndexRef = useRef(-1);
  const [status, setStatus] = useState(container.terminal?.interactive ? '进程终端' : '日志模式');
  const [connected, setConnected] = useState(false);
  const [terminalMode, setTerminalMode] = useState('log-stream');
  const [terminalInput, setTerminalInput] = useState('');
  const focusTerminalInput = () => {
    try {
      terminalInputRef.current?.focus({ preventScroll: true });
    } catch {
      terminalInputRef.current?.focus();
    }
  };

  useEffect(() => {
    let disposed = false;
    let fitTimer = null;
    let resize = () => {};
    let stopTerminalWheelLock = () => {};
    const start = async () => {
      const [{ io }, { Terminal: XTerm }, { FitAddon }] = await Promise.all([
        import('socket.io-client'),
        import('@xterm/xterm'),
        import('@xterm/addon-fit'),
      ]);
      if (disposed || !terminalRef.current) return;
      const terminal = new XTerm({
        convertEol: true,
        disableStdin: true,
        cursorBlink: false,
        cursorStyle: 'underline',
        fontSize: 14,
        lineHeight: 1.22,
        rows: 24,
        theme: {
          background: '#1e1e1e',
        },
      });
      const fitAddon = new FitAddon();
      terminal.loadAddon(fitAddon);
      terminal.open(terminalRef.current);
      fitAddon.fit();
      const wheelTarget = terminalWrapperRef.current || terminalRef.current;
      const lockTerminalWheel = (event) => {
        event.preventDefault();
        event.stopPropagation();
        event.stopImmediatePropagation?.();
        const lines = Math.max(1, Math.round(Math.abs(event.deltaY) / 42));
        terminal.scrollLines(event.deltaY > 0 ? lines : -lines);
      };
      wheelTarget?.addEventListener('wheel', lockTerminalWheel, { passive: false, capture: true });
      stopTerminalWheelLock = () => wheelTarget?.removeEventListener('wheel', lockTerminalWheel, { capture: true });
      terminal.writeln('\x1b[90mConnecting to container stream...\x1b[0m');

      const socket = io(SOCKET_BASE_URL, {
        transports: ['websocket'],
        reconnectionAttempts: 5,
      });

      terminalInstanceRef.current = terminal;
      fitAddonRef.current = fitAddon;
      socketRef.current = socket;

      const writeTerminal = (text = '', options = {}) => {
        if (!text) return;
        if (options.clear) terminal.clear();
        terminal.write(encodeConsoleColor(text).replace(/\n/g, '\r\n'));
        terminal.scrollToBottom();
      };

      const openStream = () => {
        setStatus('连接中');
        socket.emit('container/terminal/open', {
          nodeId: node.id,
          containerId: container.id,
          tail: 220,
        });
      };

      resize = () => {
        try {
          fitAddon.fit();
        } catch {
          // xterm can throw while the element is being unmounted.
        }
      };

      socket.on('connect', openStream);
      socket.on('connect_error', (error) => {
        setConnected(false);
        setStatus('连接失败');
        terminal.writeln(`\r\n\x1b[31m${friendlyError(error.message)}\x1b[0m`);
      });
      socket.on('disconnect', () => {
        setConnected(false);
        setStatus('已断开');
      });
      socket.on('container/terminal/ready', (packet) => {
        const mode = packet?.data?.mode || 'log-stream';
        setConnected(true);
        setTerminalMode(mode);
        setTerminalInput('');
        setStatus(mode === 'attach' ? '进程终端' : '日志模式');
        terminal.clear();
        writeTerminal(packet?.data?.replay || '');
      });
      socket.on('container/stdout', (packet) => {
        writeTerminal(packet?.data?.text || '');
      });
      socket.on('container/terminal/replay', (packet) => {
        const data = packet?.data || {};
        const text = data.replace ? data.text || '' : data.delta || '';
        writeTerminal(text, { clear: Boolean(data.replace) });
      });
      socket.on('container/terminal/error', (packet) => {
        setConnected(false);
        setStatus('连接失败');
        terminal.writeln(`\r\n\x1b[31m${friendlyError(packet?.message || 'Terminal stream error')}\x1b[0m`);
      });
      socket.on('container/terminal/closed', () => {
        setConnected(true);
        setStatus('等待启动');
      });
      socket.on('container/terminal/status', (packet) => {
        const nextStatus = packet?.data?.status || packet?.message || '连接中';
        setConnected(packet?.data?.connected !== false);
        setStatus(nextStatus);
      });
      socket.on('container/terminal/clear', () => terminal.clear());

      window.addEventListener('resize', resize);
      fitTimer = setInterval(resize, 1800);
    };
    start().catch((error) => {
      setConnected(false);
      setStatus(friendlyError(error.message));
    });
    return () => {
      disposed = true;
      window.removeEventListener('resize', resize);
      stopTerminalWheelLock();
      if (fitTimer) clearInterval(fitTimer);
      socketRef.current?.disconnect();
      terminalInstanceRef.current?.dispose();
      socketRef.current = null;
      terminalInstanceRef.current = null;
      fitAddonRef.current = null;
    };
  }, [container.id, node.id]);

  const clearTerminal = () => {
    terminalInstanceRef.current?.write('\x1b[2J\x1b[3J\x1b[H');
    if (terminalMode === 'attach') focusTerminalInput();
  };

  const submitTerminalCommand = () => {
    if (terminalMode !== 'attach') return;
    const nextCommand = terminalInput.trim();
    if (!nextCommand) return;
    const commandHistory = commandHistoryRef.current;
    if (commandHistory[commandHistory.length - 1] !== nextCommand) {
      commandHistoryRef.current = [...commandHistory, nextCommand].slice(-80);
    }
    historyIndexRef.current = commandHistoryRef.current.length;
    socketRef.current?.emit('container/input', {
      nodeId: node.id,
      containerId: container.id,
      command: nextCommand,
    });
    setTerminalInput('');
  };

  const handleTerminalInputKeyDown = (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      submitTerminalCommand();
      return;
    }
    if (event.key === 'ArrowUp') {
      const commandHistory = commandHistoryRef.current;
      if (!commandHistory.length) return;
      event.preventDefault();
      historyIndexRef.current = Math.max(0, historyIndexRef.current <= 0 ? commandHistory.length - 1 : historyIndexRef.current - 1);
      setTerminalInput(commandHistory[historyIndexRef.current] || '');
      return;
    }
    if (event.key === 'ArrowDown') {
      const commandHistory = commandHistoryRef.current;
      if (!commandHistory.length) return;
      event.preventDefault();
      historyIndexRef.current = Math.min(commandHistory.length, historyIndexRef.current + 1);
      setTerminalInput(historyIndexRef.current >= commandHistory.length ? '' : commandHistory[historyIndexRef.current] || '');
      return;
    }
  };

  return (
    <section className="daemon-console">
      <div className="daemon-console-title">
        <div>
          <TerminalSquare size={17} />
          <strong>{title}</strong>
          <span className={connected ? 'terminal-state online' : 'terminal-state'}>{status}</span>
        </div>
        <div className="terminal-metrics">
          <ConsoleMetricCard icon={Cpu} label="CPU 负载" value={`${Number(container.stats.cpuPercent || 0).toFixed(2)}%`} tone="blue" compact />
          <ConsoleMetricCard icon={Database} label="内存" value={`${formatBytes(container.stats.memoryUsedBytes || 0)} / ${formatBytes(container.stats.memoryLimitBytes || 0)}`} tone="purple" compact />
          <ConsoleMetricCard icon={HardDrive} label="Swap" value={formatBytes(container.stats.swapUsedBytes || 0)} tone="blue" compact />
          <ConsoleMetricCard icon={Network} label="网络" value={[`↓ ${formatRate(container.stats.networkDownloadBps || 0)}`, `↑ ${formatRate(container.stats.networkUploadBps || 0)}`]} tone="green" compact />
        </div>
      </div>
      <div className="console-wrapper">
        {!connected && <div className="terminal-loading">连接中</div>}
        <div className="terminal-button-group">
          <button onClick={clearTerminal} title="清屏" type="button">清屏</button>
        </div>
        <div className="terminal-wrapper" ref={terminalWrapperRef} onClick={() => {
          if (terminalMode === 'attach') focusTerminalInput();
        }}>
          <div className="terminal-container">
            <div className="xterm-host" ref={terminalRef}></div>
          </div>
          <div className="terminal-input-row">
            <span>$</span>
            <input
              aria-label="容器终端命令"
              autoComplete="off"
              disabled={terminalMode !== 'attach'}
              onChange={(event) => setTerminalInput(event.target.value)}
              onKeyDown={handleTerminalInputKeyDown}
              placeholder={terminalMode === 'attach' ? '输入命令，回车发送到进程' : '日志模式不可输入'}
              ref={terminalInputRef}
              spellCheck="false"
              type="text"
              value={terminalInput}
            />
          </div>
        </div>
      </div>
    </section>
  );
}

function NetworkView() {
  return (
    <section className="page">
      <PageHead title="网络与存储" desc="管理公网入口、内网地址、卷挂载、备份和快照策略。" action="新增网关" />
      <div className="content-grid">
        <Panel title="网络入口" action="配置" icon={Network}>
          {['play.beiming.cn:25565', 'panel.beiming.cn', 'api.beiming.cn'].map((item) => (
            <InfoRow key={item} label={item} value="已解析 / TLS 正常" />
          ))}
        </Panel>
        <Panel title="存储卷" action="备份" icon={HardDrive}>
          {['mc-world-data 18.2GB', 'postgres-data 4.7GB', 'container-cache 1.1GB'].map((item) => (
            <InfoRow key={item} label={item} value="每日快照" />
          ))}
        </Panel>
      </div>
    </section>
  );
}

function IdentityView() {
  return (
    <section className="page">
      <PageHead title="用户与账号绑定" desc="用户管理、QQ 绑定、邮箱验证、Daemon Token 和资源授权都在这里。" action="邀请用户" />
      <div className="content-grid">
        <Panel title="成员" action="新增成员" icon={UsersRound}>
          <UserTable />
        </Panel>
        <Panel title="当前账号绑定" action="绑定 QQ" icon={CircleUserRound}>
          <AccountBindings />
        </Panel>
      </div>
    </section>
  );
}

function SecurityView() {
  return (
    <section className="page">
      <PageHead title="安全审计" desc="登录保护、资源操作审计、敏感命令记录与异常登录阻断。" action="导出日志" />
      <div className="content-grid">
        <Panel title="审计事件" action="筛选" icon={ShieldCheck}>
          <ActivityFeed />
        </Panel>
        <Panel title="安全基线" action="编辑策略" icon={LockKeyhole}>
          <SecurityRules />
        </Panel>
      </div>
    </section>
  );
}

function RemoteNodesView({ nodes, notify, onNodesChange }) {
  const [checks, setChecks] = useState({});
  const [metrics, setMetrics] = useState({});
  const [networkHistory, setNetworkHistory] = useState({});
  const [activeNetworkNodeId, setActiveNetworkNodeId] = useState('');
  const [editingNode, setEditingNode] = useState(null);
  const [deletingNode, setDeletingNode] = useState(null);
  const [refreshing, setRefreshing] = useState(false);

  useEffect(() => {
    if (!activeNetworkNodeId && nodes[0]?.id) {
      setActiveNetworkNodeId(nodes[0].id);
    }
  }, [activeNetworkNodeId, nodes]);

  useEffect(() => {
    if (nodes.length === 0) return;
    let ignore = false;
    setChecks((current) => {
      const next = { ...current };
      nodes.forEach((node) => {
        next[node.id] = { status: 'checking', message: '连接中' };
      });
      return next;
    });
    nodes.forEach((node) => {
      pingNode(node.id)
        .then((result) => {
          if (ignore) return;
          setChecks((current) => ({
            ...current,
            [node.id]: { status: 'ok', message: result.output || '连接正常' },
          }));
        })
        .catch((error) => {
          if (ignore) return;
          setChecks((current) => ({
            ...current,
            [node.id]: { status: 'error', message: friendlyError(error.message) },
          }));
        });
    });
    return () => {
      ignore = true;
    };
  }, [nodes]);

  useEffect(() => {
    let ignore = false;
    const loadMetrics = async () => {
      const results = await Promise.allSettled(nodes.map(async (node) => [node.id, await fetchNodeMetrics(node.id)]));
      if (ignore) return;
      setMetrics((current) => {
        const next = { ...current };
        results.forEach((result) => {
          if (result.status === 'fulfilled') {
            const [nodeId, data] = result.value;
            next[nodeId] = { status: 'ok', data };
            setNetworkHistory((current) => {
              const existing = current[nodeId] || [];
              const isFirstSample = existing.length === 0;
              const sample = {
                download: isFirstSample ? 0 : data.network?.downloadBps || 0,
                upload: isFirstSample ? 0 : data.network?.uploadBps || 0,
                downloadTotal: data.networkTotals?.downloadBytes || 0,
                uploadTotal: data.networkTotals?.uploadBytes || 0,
                time: data.updatedAt || Date.now(),
              };
              return {
                ...current,
                [nodeId]: [...existing, sample].slice(-60),
              };
            });
          } else {
            const node = nodes[results.indexOf(result)];
            if (node) next[node.id] = { status: 'error', message: friendlyError(result.reason.message) };
          }
        });
        return next;
      });
    };
    loadMetrics();
    const timer = setInterval(loadMetrics, 1000);
    return () => {
      ignore = true;
      clearInterval(timer);
    };
  }, [nodes]);

  const checkNode = async (node) => {
    setChecks((current) => ({
      ...current,
      [node.id]: { status: 'checking', message: '检测中' },
    }));
    try {
      const result = await pingNode(node.id);
      setChecks((current) => ({
        ...current,
        [node.id]: { status: 'ok', message: result.output || '连接正常' },
      }));
      notify({ title: '节点连接正常', message: node.name });
    } catch (error) {
      setChecks((current) => ({
        ...current,
        [node.id]: { status: 'error', message: friendlyError(error.message) },
      }));
      notify({ type: 'error', title: '节点连接失败', message: friendlyError(error.message), duration: 4200 });
    }
  };

  const reloadNodes = async () => {
    setRefreshing(true);
    setChecks((current) => {
      const next = { ...current };
      nodes.forEach((node) => {
        next[node.id] = { status: 'checking', message: '连接中' };
      });
      return next;
    });
    setMetrics({});
    setNetworkHistory({});
    try {
      const nextNodes = await fetchNodes();
      onNodesChange(nextNodes);
      notify({ title: '节点列表已刷新', message: `${nextNodes.length} 个节点` });
    } catch (error) {
      notify({ type: 'error', title: '节点列表刷新失败', message: friendlyError(error.message), duration: 4200 });
    } finally {
      setRefreshing(false);
    }
  };

  const removeNode = async () => {
    if (!deletingNode) return;
    try {
      await deleteNode(deletingNode.id);
      onNodesChange((current) => current.filter((item) => item.id !== deletingNode.id));
      setChecks((current) => {
        const next = { ...current };
        delete next[deletingNode.id];
        return next;
      });
      setMetrics((current) => {
        const next = { ...current };
        delete next[deletingNode.id];
        return next;
      });
      setNetworkHistory((current) => {
        const next = { ...current };
        delete next[deletingNode.id];
        return next;
      });
      setActiveNetworkNodeId((current) => current === deletingNode.id ? '' : current);
      notify({ title: '节点已删除', message: deletingNode.name });
      setDeletingNode(null);
    } catch (error) {
      notify({ type: 'error', title: '节点删除失败', message: friendlyError(error.message), duration: 4200 });
    }
  };

  return (
    <section className="page">
      <PageHead title="远程节点" desc="通过北冥 daemon 管理共享配置服务器，容器与虚拟机统一从 daemon 节点读取。" action="新增节点" onAction={() => setEditingNode({ mode: 'create' })} />
      <Panel title="节点列表" action={refreshing ? '刷新中...' : '刷新'} icon={ServerCog} onAction={reloadNodes}>
        <div className="node-table">
          <div className="node-row node-head">
            <span>节点</span>
            <span>CPU</span>
            <span>内存</span>
            <span>Swap</span>
            <span>网络</span>
            <span></span>
          </div>
          {nodes.map((node) => {
            const check = checks[node.id];
            const metric = metrics[node.id];
            return (
              <div
                className={[
                  check?.status === 'error' ? 'node-row has-error' : 'node-row',
                  activeNetworkNodeId === node.id ? 'selected' : '',
                ].filter(Boolean).join(' ')}
                key={node.id}
                onClick={() => setActiveNetworkNodeId(node.id)}
              >
                <div className="node-title">
                  <div className="metric-icon"><ServerCog size={18} /></div>
                  <div>
                    <div className="node-name-line">
                      <strong>{node.name}</strong>
                      <NodeHealth metric={metric} check={check} />
                    </div>
                    <span>{node.id}</span>
                  </div>
                </div>
                <NodeMetric metric={metric} field="cpu" />
                <NodeMetric metric={metric} field="memory" />
                <NodeMetric metric={metric} field="swap" />
                <NetworkRate metric={metric} samples={networkHistory[node.id] || []} />
                <div className="node-actions">
                  <button className="link-action" onClick={() => setEditingNode(node)} type="button">编辑</button>
                  <button className="link-action" onClick={() => checkNode(node)} type="button">检测</button>
                  <button className="link-action danger" onClick={() => setDeletingNode(node)} type="button">删除</button>
                </div>
                {check?.status === 'error' && <span className="node-inline-error">连接检测失败：{check.message}</span>}
              </div>
            );
          })}
        </div>
      </Panel>
      {editingNode && (
        <NodeEditModal
          node={editingNode}
          onClose={() => setEditingNode(null)}
          notify={notify}
          onSaved={async (savedNode) => {
            const nextNodes = await fetchNodes();
            onNodesChange(nextNodes);
            setEditingNode(null);
            notify({ title: editingNode.mode === 'create' ? '节点已添加' : '节点配置已保存', message: savedNode.name });
          }}
        />
      )}
      {deletingNode && (
        <ConfirmDialog
          desc="删除后该节点的容器和虚拟机将不再显示，节点连接配置也会从本地配置中移除。"
          title={`删除节点「${deletingNode.name}」`}
          onCancel={() => setDeletingNode(null)}
          onConfirm={removeNode}
        />
      )}
    </section>
  );
}

function ConfirmDialog({ title, desc, onCancel, onConfirm }) {
  useBodyScrollLock();
  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onCancel();
      }}
    >
      <section className="confirm-modal" role="dialog" aria-modal="true" aria-label={title}>
        <div className="confirm-icon"><AlertCircle size={22} /></div>
        <div>
          <h2>{title}</h2>
          <p>{desc}</p>
        </div>
        <div className="modal-actions">
          <button className="secondary" onClick={onCancel} type="button">取消</button>
          <button className="danger-primary" onClick={onConfirm} type="button">确认删除</button>
        </div>
      </section>
    </div>
  );
}

function NodeHealth({ metric, check }) {
  if (check?.status === 'checking') return <HealthBadge tone="pending" label="检测中" />;
  if (check?.status === 'ok') return <HealthBadge tone="ok" label="在线" />;
  if (check?.status === 'error') return <HealthBadge tone="danger" label="离线" />;
  if (!metric) return <HealthBadge tone="pending" label="连接中" />;
  if (metric.status === 'error') return <HealthBadge tone="danger" label="离线" title={metric.message} />;
  return <HealthBadge tone="ok" label="在线" />;
}

function HealthBadge({ tone, label, title }) {
  return (
    <span className={`node-health ${tone}`} title={title}>
      <StatusIcon tone={tone} />
      <span>{label}</span>
    </span>
  );
}

function NodeMetric({ metric, field }) {
  if (!metric) return <MetricPlaceholder loading />;
  if (metric.status === 'error') return <MetricPlaceholder />;
  const value = field === 'cpu' ? metric.data.cpu : metric.data[field]?.percent;
  const label = value === null || value === undefined ? '-' : `${Number(value).toFixed(2)}%`;
  const subLabel = getMetricSubLabel(metric.data, field);
  return (
    <div className="metric-mini">
      <b>{label}</b>
      {subLabel && <small>{subLabel}</small>}
      <i><span style={{ width: value ? `${Math.min(value, 100)}%` : '0%' }}></span></i>
    </div>
  );
}

function getMetricSubLabel(data, field) {
  if (field === 'cpu') {
    const cores = data.cpuSpec?.cores;
    const threads = data.cpuSpec?.threads || cores;
    if (!cores && !threads) return '';
    return `${cores || threads}C / ${threads || cores}T`;
  }
  if (field === 'memory' || field === 'swap') {
    const usedGb = Number(data[field]?.usedGb || 0);
    const totalGb = Number(data[field]?.totalGb || 0);
    return `${usedGb.toFixed(2)}G/${totalGb.toFixed(2)}G`;
  }
  return '';
}

function NetworkRate({ metric, samples = [] }) {
  if (!metric) return <MetricPlaceholder loading compact />;
  if (metric.status === 'error') return <MetricPlaceholder compact />;
  const priming = samples.length < 2;
  const download = priming ? 0 : metric.data.network?.downloadBps || 0;
  const upload = priming ? 0 : metric.data.network?.uploadBps || 0;
  return (
    <div className="net-rate">
      <div className="net-rate-head">
        <span><Download size={13} /></span>
        <b>{formatRate(download)}</b>
        <span className="upload-icon"><Upload size={13} /></span>
        <b>{formatRate(upload)}</b>
      </div>
      <MiniNetworkChart samples={samples} download={download} upload={upload} />
    </div>
  );
}

function MiniNetworkChart({ samples, download, upload }) {
  const chartSamples = samples.length > 1 ? samples.slice(-24) : [
    { download: 0, upload: 0 },
    { download, upload },
  ];
  const width = 190;
  const height = 34;
  const maxValue = Math.max(1024, ...chartSamples.flatMap((item) => [item.download || 0, item.upload || 0]));
  const points = (field) => chartSamples.map((sample, index) => {
    const x = chartSamples.length === 1 ? 0 : (index / (chartSamples.length - 1)) * width;
    const y = height - (Math.min(sample[field] || 0, maxValue) / maxValue) * (height - 4) - 2;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(' ');
  return (
    <svg className="mini-net-chart" viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
      <line x1="0" x2={width} y1={height - 4} y2={height - 4} />
      <polyline className="download-line" points={points('download')} />
      <polyline className="upload-line" points={points('upload')} />
    </svg>
  );
}

function MetricPlaceholder({ loading = false, compact = false }) {
  return (
    <div className={[compact ? 'metric-mini placeholder compact' : 'metric-mini placeholder', loading ? 'loading' : ''].filter(Boolean).join(' ')}>
      <b>{loading ? '' : '--'}</b>
      {!compact && <i><span></span></i>}
    </div>
  );
}

function NodeEditModal({ node, notify, onClose, onSaved }) {
  useBodyScrollLock();
  const isCreate = node.mode === 'create';
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [form, setForm] = useState({
    id: '',
    name: node.name || '',
    daemonUrl: getNodeDaemonUrl(node),
    daemonToken: '',
  });

  const setField = (field, value) => {
    setForm((current) => ({ ...current, [field]: value }));
  };

  const save = async () => {
    setSaving(true);
    setError('');
    try {
      const savedNode = isCreate
        ? await createNode(form)
        : await updateNode(node.id, form);
      await onSaved(savedNode);
    } catch (saveError) {
      const message = friendlyError(saveError.message);
      setError(message);
      notify({ type: 'error', title: isCreate ? '节点添加失败' : '节点配置保存失败', message, duration: 4600 });
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !saving) onClose();
      }}
    >
      <section className="node-modal" role="dialog" aria-modal="true" aria-label="编辑远程节点">
        <div className="modal-head">
          <div>
            <h2>{isCreate ? '新增远程节点' : '编辑远程节点'}</h2>
          </div>
          <button className="modal-close" onClick={onClose} type="button">关闭</button>
        </div>

        <div className="form-grid">
          {isCreate && (
            <label>
              节点 ID
              <input value={form.id} onChange={(event) => setField('id', event.target.value)} placeholder="留空自动生成" />
            </label>
          )}
          <label>
            节点名称
            <input value={form.name} onChange={(event) => setField('name', event.target.value)} />
          </label>
          <label>
            Daemon 地址
            <input value={form.daemonUrl} onChange={(event) => setField('daemonUrl', event.target.value)} placeholder="http://127.0.0.1:8790" />
          </label>
        </div>

        <div className="auth-block">
          <span className="field-label">Daemon 认证</span>
          <label>
            访问 Token
            <input type="password" value={form.daemonToken} onChange={(event) => setField('daemonToken', event.target.value)} placeholder={node.hasDaemonToken ? '留空则保持原 token' : '可留空'} />
          </label>
        </div>

        {error && <div className="modal-error">{friendlyError(error)}</div>}

        <div className="modal-actions">
          <button className="secondary" onClick={onClose} type="button">取消</button>
          <button className="primary" disabled={saving} onClick={save} type="button">{saving ? '保存中...' : isCreate ? '添加节点' : '保存配置'}</button>
        </div>
      </section>
    </div>
  );
}

function NodeCheckStatus({ check }) {
  if (!check) return <span className="check-status idle">未检测</span>;
  if (check.status === 'checking') return <span className="check-status pending">检测中</span>;
  if (check.status === 'ok') return <span className="check-status ok">连接正常</span>;
  return <span className="check-status error" title={check.message}>连接失败</span>;
}

function PageHead({ title, desc, action, onAction }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        <span>{desc}</span>
      </div>
      <button className="primary" onClick={onAction} type="button"><Plus size={17} />{action}</button>
    </div>
  );
}

function Metric({ icon: Icon, label, value, trend }) {
  return (
    <article className="metric">
      <div className="metric-icon"><Icon size={20} /></div>
      <span>{label}</span>
      <strong>{value}</strong>
      <em>{trend}</em>
    </article>
  );
}

function Panel({ title, action, icon: Icon, children, onAction }) {
  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <Icon size={18} />
          <h2>{title}</h2>
        </div>
        {action && <button onClick={onAction} type="button">{action}</button>}
      </div>
      {children}
    </section>
  );
}

function ResourceTable({ resources }) {
  return (
    <div className="resource-table">
      <div className="resource-row table-head">
        <span>资源</span>
        <span>地域</span>
        <span>规格</span>
        <span>负载</span>
        <span>状态</span>
        <span>入口</span>
      </div>
      {resources.map((item) => (
        <div className="resource-row" key={item.id}>
          <div className="resource-name">
            <KindIcon kind={item.kind} />
            <div>
              <strong>{item.name}</strong>
              <span>{item.kind} · {item.owner}</span>
            </div>
          </div>
          <span>{item.region}</span>
          <span>{item.plan}</span>
          <div className="load-cell">
            <i style={{ width: `${item.load}%` }}></i>
            <b>{item.load}%</b>
          </div>
          <Status status={item.status} />
          <code>{item.endpoint}</code>
        </div>
      ))}
    </div>
  );
}

function KindIcon({ kind }) {
  const Icon = kind === 'Minecraft' ? Server : kind === 'Container' ? Container : MonitorCog;
  return <div className="kind-icon"><Icon size={18} /></div>;
}

function Status({ status }) {
  const className = status === '运行中' ? 'ok' : status === '部署中' ? 'pending' : 'warn';
  return <span className={`status ${className}`}>{status}</span>;
}

function ActivityFeed() {
  return (
    <div className="feed">
      {activityTasks.map((task) => (
        <div className="feed-item" key={task.title}>
          <span className={`dot ${task.tone}`}></span>
          <div>
            <strong>{task.title}</strong>
            <p>{task.desc}</p>
          </div>
          <em>{task.time}</em>
        </div>
      ))}
    </div>
  );
}

function AccountBindings() {
  return (
    <div className="binding-list">
      {accounts.map((account) => {
        const Icon = account.icon;
        return (
          <div className="binding" key={account.provider}>
            <div className="binding-icon"><Icon size={18} /></div>
            <div>
              <strong>{account.provider}</strong>
              <span>{account.value}</span>
            </div>
            <b>{account.status}</b>
          </div>
        );
      })}
    </div>
  );
}

function QuotaPanel() {
  return (
    <div className="quota">
      <Quota label="CPU 核心" value={46} />
      <Quota label="内存" value={62} />
      <Quota label="存储" value={38} />
      <Quota label="公网带宽" value={71} />
    </div>
  );
}

function Quota({ label, value }) {
  return (
    <div className="quota-row">
      <div>
        <span>{label}</span>
        <strong>{value}%</strong>
      </div>
      <i><b style={{ width: `${value}%` }}></b></i>
    </div>
  );
}

function UserTable() {
  return (
    <div className="user-list">
      {demoUsers.map((user) => (
        <div className="user-row" key={user.email}>
          <div className="mini-avatar">{user.name.slice(0, 1)}</div>
          <div>
            <strong>{user.name}</strong>
            <span>{user.email}</span>
          </div>
          <span>{user.role}</span>
          <span>{user.binding}</span>
          <Status status={user.status === '正常' ? '运行中' : user.status === '待补全' ? '部署中' : '维护中'} />
        </div>
      ))}
    </div>
  );
}

function SecurityRules() {
  const rules = ['邮箱验证后才能创建资源', 'QQ 绑定后允许提交工单', '高危操作需要二次确认', '容器 exec 命令写入审计日志'];
  return (
    <div className="rule-list">
      {rules.map((rule) => (
        <div className="rule" key={rule}>
          <CheckCircle2 size={17} />
          <span>{rule}</span>
        </div>
      ))}
    </div>
  );
}

function Signal({ label, value }) {
  return (
    <div className="signal">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function InfoRow({ label, value }) {
  return (
    <div className="info-row">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

export default App;
