const TERM_COLOR = {
  TERM_RESET: '\x1B[0m',
  TERM_TEXT_BLACK: '\x1B[0;30m',
  TERM_TEXT_DARK_BLUE: '\x1B[0;34m',
  TERM_TEXT_DARK_GREEN: '\x1B[0;32m',
  TERM_TEXT_DARK_AQUA: '\x1B[0;36m',
  TERM_TEXT_DARK_RED: '\x1B[0;31m',
  TERM_TEXT_DARK_PURPLE: '\x1B[0;35m',
  TERM_TEXT_GOLD: '\x1B[0;33m',
  TERM_TEXT_GRAY: '\x1B[0;37m',
  TERM_TEXT_DARK_GRAY: '\x1B[0;30;1m',
  TERM_TEXT_BLUE: '\x1B[0;34;1m',
  TERM_TEXT_GREEN: '\x1B[0;32;1m',
  TERM_TEXT_AQUA: '\x1B[0;36;1m',
  TERM_TEXT_RED: '\x1B[0;31;1m',
  TERM_TEXT_LIGHT_PURPLE: '\x1B[0;35;1m',
  TERM_TEXT_YELLOW: '\x1B[0;33;1m',
  TERM_TEXT_WHITE: '\x1B[0;37;1m',
  TERM_TEXT_OBFUSCATED: '\x1B[5m',
  TERM_TEXT_BOLD: '\x1B[21m',
  TERM_TEXT_STRIKETHROUGH: '\x1B[9m',
  TERM_TEXT_UNDERLINE: '\x1B[4m',
  TERM_TEXT_ITALIC: '\x1B[3m',
};

export function formatRate(bytesPerSecond) {
  if (bytesPerSecond >= 1024 * 1024) return `${(bytesPerSecond / 1024 / 1024).toFixed(2)} MB/s`;
  if (bytesPerSecond >= 1024) return `${(bytesPerSecond / 1024).toFixed(2)} KB/s`;
  return `${bytesPerSecond} B/s`;
}

export function formatBytes(bytes) {
  if (!bytes) return '0 B';
  if (bytes >= 1024 ** 4) return `${(bytes / 1024 ** 4).toFixed(2)} TB`;
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(2)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(2)} KB`;
  return `${bytes} B`;
}

export function getDownloadPlan(fileSize = 0) {
  const size = Number(fileSize || 0);
  const mib = 1024 * 1024;
  const chunkSize = size < 256 * mib
    ? 4 * mib
    : size < 2 * 1024 * mib
      ? 8 * mib
      : size < 8 * 1024 * mib
        ? 16 * mib
        : 32 * mib;
  const totalChunks = Math.max(1, Math.ceil(size / chunkSize));
  const workerLimit = size < 64 * mib ? 4 : 16;
  return {
    chunkSize,
    workerCount: Math.max(1, Math.min(workerLimit, totalChunks)),
  };
}

export function getUploadPlan(fileSize = 0, workerCap = 12) {
  const size = Number(fileSize || 0);
  const mib = 1024 * 1024;
  const chunkSize = size < 128 * mib
    ? 4 * mib
    : size < 1024 * mib
      ? 8 * mib
      : size < 8 * 1024 * mib
        ? 16 * mib
        : 32 * mib;
  const totalChunks = Math.max(1, Math.ceil(size / chunkSize));
  const workerLimit = size < 64 * mib ? 3 : size < 1024 * mib ? 6 : 10;
  return {
    chunkSize,
    workerCount: Math.max(1, Math.min(workerLimit, workerCap, totalChunks)),
  };
}

export function joinContainerPath(base = '/', name = '') {
  const cleanBase = base === '/' ? '' : String(base || '').replace(/\/+$/, '');
  return `${cleanBase}/${String(name || '').replace(/^\/+/, '')}` || '/';
}

export function normalizeContainerPath(value = '/') {
  const text = String(value || '/').trim();
  if (!text || text === 'root') return '/';
  return text.startsWith('/') ? text : `/${text}`;
}

export function nextNewEntryName(baseName, existingNames = []) {
  const used = new Set(existingNames);
  if (!used.has(baseName)) return baseName;
  for (let index = 2; index < 10000; index += 1) {
    const candidate = `${baseName} (${index})`;
    if (!used.has(candidate)) return candidate;
  }
  return `${baseName}-${Date.now()}`;
}

export function normalizeRect(startX, startY, endX, endY) {
  return {
    left: Math.min(startX, endX),
    right: Math.max(startX, endX),
    top: Math.min(startY, endY),
    bottom: Math.max(startY, endY),
  };
}

export function rectsIntersect(left, right) {
  return left.left <= right.right
    && left.right >= right.left
    && left.top <= right.bottom
    && left.bottom >= right.top;
}

export function formatFileTime(value) {
  const seconds = Number(value || 0);
  if (!seconds) return '-';
  return new Date(seconds * 1000).toLocaleString();
}

export function saveBlobFile(blob, name) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name || 'download';
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export function formatContainerPorts(ports = []) {
  if (!ports || ports.length === 0) return '未映射';
  return ports
    .map((port) => port.host ? `${port.host} -> ${port.containerPort}` : port.containerPort)
    .join('，');
}

export function parseContainerPort(value = '') {
  const [port, protocol = ''] = String(value).split('/');
  return { port, protocol };
}

export function formatHostPort(host = '') {
  if (!host) return '-';
  const parts = String(host).split(',');
  const first = parts[0]?.trim() || '';
  const port = first.match(/:(\d+)$/)?.[1] || first;
  return port || '-';
}

export function splitLines(text = '') {
  return String(text)
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean);
}

export function formatPortTitle(port) {
  return port.host ? `${port.host} -> ${port.containerPort}` : port.containerPort;
}

export function makeEditId() {
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export function portsToEditRows(ports = []) {
  const seen = new Set();
  return (ports || []).flatMap((port) => {
    const target = parseContainerPort(port.containerPort);
    const hosts = String(port.host || '').split(',').map((item) => item.trim()).filter(Boolean);
    const rows = hosts.length > 0 ? hosts : [''];
    return rows
      .map((host) => {
        const hostPort = formatHostPort(host) === '-' ? '' : formatHostPort(host);
        const row = {
          id: makeEditId(),
          hostPort,
          containerPort: target.port || '',
          protocol: target.protocol || 'tcp',
        };
        const key = `${row.hostPort}:${row.containerPort}/${row.protocol}`;
        if (seen.has(key)) return null;
        seen.add(key);
        return row;
      })
      .filter(Boolean);
  });
}

export function editRowsToPortSpecs(rows = []) {
  return rows
    .map((row) => {
      const hostPort = String(row.hostPort || '').trim();
      const containerPort = String(row.containerPort || '').trim();
      const protocol = String(row.protocol || 'tcp').trim().toLowerCase();
      if (!hostPort || !containerPort) return '';
      return `${hostPort}:${containerPort}${protocol && protocol !== 'tcp' ? `/${protocol}` : ''}`;
    })
    .filter(Boolean);
}

export function keyValueStringsToRows(values = []) {
  return (values || []).map((item) => {
    const [key, ...rest] = String(item).split('=');
    return { id: makeEditId(), key: key || '', value: rest.join('=') };
  });
}

export function editRowsToEnvSpecs(rows = []) {
  return rows
    .map((row) => {
      const key = String(row.key || '').trim();
      if (!key) return '';
      return `${key}=${String(row.value || '')}`;
    })
    .filter(Boolean);
}

export function mountStringsToRows(values = []) {
  return (values || []).map((item) => {
    const spec = typeof item === 'string' ? item : item?.spec || '';
    const explicitType = typeof item === 'string' ? '' : item?.type || '';
    const parts = String(spec).split(':');
    const source = parts[0] || '';
    return {
      id: makeEditId(),
      type: explicitType || (source.startsWith('/') || source.startsWith('.') ? 'bind' : 'volume'),
      host: source,
      container: parts[1] || '',
      mode: parts[2] === 'ro' ? 'ro' : 'rw',
    };
  });
}

export function splitDockerCommand(command = '') {
  const tokens = [];
  let current = '';
  let quote = '';
  let escaping = false;
  for (const char of String(command).trim()) {
    if (escaping) {
      current += char;
      escaping = false;
      continue;
    }
    if (char === '\\') {
      escaping = true;
      continue;
    }
    if (quote) {
      if (char === quote) quote = '';
      else current += char;
      continue;
    }
    if (char === '"' || char === "'") {
      quote = char;
      continue;
    }
    if (/\s/.test(char)) {
      if (current) {
        tokens.push(current);
        current = '';
      }
      continue;
    }
    current += char;
  }
  if (current) tokens.push(current);
  return tokens;
}

export function parseDockerRunCommand(command = '') {
  let tokens = splitDockerCommand(command.replace(/\\\r?\n/g, ' '));
  if (tokens[0] === 'sudo') tokens = tokens.slice(1);
  if (tokens[0] === 'docker') tokens = tokens.slice(1);
  if (tokens[0] === 'container') tokens = tokens.slice(1);
  if (tokens[0] !== 'run') throw new Error('请粘贴 docker run 命令');
  tokens = tokens.slice(1);
  const parsed = {
    name: '',
    image: '',
    restartPolicy: 'no',
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
    stdinOpen: false,
    tty: false,
  };
  const readValue = (index, token) => {
    const equalIndex = token.indexOf('=');
    if (equalIndex > -1) return { value: token.slice(equalIndex + 1), nextIndex: index };
    return { value: tokens[index + 1] || '', nextIndex: index + 1 };
  };
  const addPortSpec = (spec) => {
    const [main, protocol = 'tcp'] = String(spec).split('/');
    const parts = main.split(':');
    const containerPort = parts.pop() || '';
    const hostPort = parts.pop() || '';
    if (!containerPort) return;
    parsed.ports.push({ id: makeEditId(), hostPort, containerPort, protocol: protocol || 'tcp' });
  };
  const addMountSpec = (spec) => {
    const parts = String(spec).split(':');
    const host = parts[0] || '';
    const container = parts[1] || '';
    const mode = parts[2] === 'ro' ? 'ro' : 'rw';
    if (!host || !container) return;
    parsed.mounts.push({ id: makeEditId(), type: host.startsWith('/') || host.startsWith('.') ? 'bind' : 'volume', host, container, mode });
  };
  const addEnvSpec = (spec) => {
    const [key, ...rest] = String(spec).split('=');
    if (!key) return;
    parsed.env.push({ id: makeEditId(), key, value: rest.join('=') });
  };

  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (!token.startsWith('-')) {
      parsed.image = token;
      parsed.command = tokens.slice(index + 1).join(' ');
      break;
    }
    if (token === '-d' || token === '--detach' || token === '--rm') continue;
    if (token === '-i' || token.includes('i') && /^-[it]+$/.test(token)) parsed.stdinOpen = true;
    if (token === '-t' || token.includes('t') && /^-[it]+$/.test(token)) parsed.tty = true;
    if (['--privileged'].includes(token)) {
      parsed.privileged = true;
      continue;
    }
    if (token === '--name' || token.startsWith('--name=')) {
      const result = readValue(index, token);
      parsed.name = result.value;
      index = result.nextIndex;
      continue;
    }
    if (token === '--restart' || token.startsWith('--restart=')) {
      const result = readValue(index, token);
      parsed.restartPolicy = result.value || 'no';
      index = result.nextIndex;
      continue;
    }
    if (token === '--network' || token === '--net' || token.startsWith('--network=') || token.startsWith('--net=')) {
      const result = readValue(index, token);
      parsed.networkMode = result.value || 'bridge';
      index = result.nextIndex;
      continue;
    }
    if (token === '-p' || token === '--publish' || token.startsWith('--publish=')) {
      const result = readValue(index, token);
      addPortSpec(result.value);
      index = result.nextIndex;
      continue;
    }
    if (token.startsWith('-p') && token.length > 2) {
      addPortSpec(token.slice(2));
      continue;
    }
    if (token === '-e' || token === '--env' || token.startsWith('--env=')) {
      const result = readValue(index, token);
      addEnvSpec(result.value);
      index = result.nextIndex;
      continue;
    }
    if (token.startsWith('-e') && token.length > 2) {
      addEnvSpec(token.slice(2));
      continue;
    }
    if (token === '-v' || token === '--volume' || token.startsWith('--volume=')) {
      const result = readValue(index, token);
      addMountSpec(result.value);
      index = result.nextIndex;
      continue;
    }
    if (token.startsWith('-v') && token.length > 2) {
      addMountSpec(token.slice(2));
      continue;
    }
    if (token === '-w' || token === '--workdir' || token.startsWith('--workdir=')) {
      const result = readValue(index, token);
      parsed.workingDir = result.value;
      index = result.nextIndex;
      continue;
    }
    if (token === '--cpus' || token.startsWith('--cpus=')) {
      const result = readValue(index, token);
      parsed.cpuLimit = result.value;
      index = result.nextIndex;
      continue;
    }
    if (token === '-m' || token === '--memory' || token.startsWith('--memory=')) {
      const result = readValue(index, token);
      parsed.memoryLimit = result.value;
      index = result.nextIndex;
      continue;
    }
    if (!token.includes('=')) index += token.startsWith('--') ? 1 : 0;
  }
  if (!parsed.image) throw new Error('没有识别到镜像名称');
  if (!parsed.name) parsed.name = parsed.image.split('/').pop().split(':')[0].replace(/[^a-zA-Z0-9_.-]/g, '-');
  return parsed;
}

export function editRowsToMountSpecs(rows = []) {
  return rows
    .map((row) => {
      const host = String(row.host || '').trim();
      const container = String(row.container || '').trim();
      if (!host || !container) return '';
      return `${host}:${container}:${row.mode === 'ro' ? 'ro' : 'rw'}`;
    })
    .filter(Boolean);
}

export function bytesToMemoryInput(bytes) {
  const value = Number(bytes || 0);
  if (!value) return '';
  if (value >= 1024 ** 3) return `${Number((value / 1024 ** 3).toFixed(2))}g`;
  if (value >= 1024 ** 2) return `${Math.round(value / 1024 ** 2)}m`;
  return String(value);
}

export function formatRestartPolicy(policy = 'no') {
  const labels = {
    no: 'No',
    always: 'Always',
    'unless-stopped': 'Unless stopped',
    'on-failure': 'On failure',
  };
  return labels[policy] || policy;
}

export function renderTerminalOutput(output = '') {
  return output.replace(/\u001b\[[0-9;]*m/g, '');
}

export function encodeConsoleColor(text = '') {
  let nextText = String(text);
  nextText = nextText.replace(/(\x1B[^m]*m)/gm, '$1;');
  nextText = nextText.replace(/ \[([A-Za-z0-9 _\-.]+)]/gim, ` [${TERM_COLOR.TERM_TEXT_DARK_AQUA}$1${TERM_COLOR.TERM_RESET}]`);
  nextText = nextText.replace(/^\[([A-Za-z0-9 _\-.]+)]/gim, `[${TERM_COLOR.TERM_TEXT_DARK_AQUA}$1${TERM_COLOR.TERM_RESET}]`);
  nextText = nextText.replace(/((["'])(.*?)\2)/gm, `${TERM_COLOR.TERM_TEXT_YELLOW}$1${TERM_COLOR.TERM_RESET}`);
  nextText = nextText.replace(/([0-9]{1,2}:[0-9]{1,2}:[0-9]{1,2})/gim, `${TERM_COLOR.TERM_TEXT_GOLD}$1${TERM_COLOR.TERM_RESET}`);
  nextText = nextText.replace(/([0-9]{2,4}[/-][0-9]{2,4}[/-][0-9]{2,4})/gim, `${TERM_COLOR.TERM_TEXT_GOLD}$1${TERM_COLOR.TERM_RESET}`);
  nextText = nextText.replace(/(\x1B[^m]*m);/gm, '$1');

  const colorMap = {
    0: TERM_COLOR.TERM_TEXT_BLACK,
    1: TERM_COLOR.TERM_TEXT_DARK_BLUE,
    2: TERM_COLOR.TERM_TEXT_DARK_GREEN,
    3: TERM_COLOR.TERM_TEXT_DARK_AQUA,
    4: TERM_COLOR.TERM_TEXT_DARK_RED,
    5: TERM_COLOR.TERM_TEXT_DARK_PURPLE,
    6: TERM_COLOR.TERM_TEXT_GOLD,
    7: TERM_COLOR.TERM_TEXT_GRAY,
    8: TERM_COLOR.TERM_TEXT_DARK_GRAY,
    9: TERM_COLOR.TERM_TEXT_BLUE,
    a: TERM_COLOR.TERM_TEXT_GREEN,
    b: TERM_COLOR.TERM_TEXT_AQUA,
    c: TERM_COLOR.TERM_TEXT_RED,
    d: TERM_COLOR.TERM_TEXT_LIGHT_PURPLE,
    e: TERM_COLOR.TERM_TEXT_YELLOW,
    f: TERM_COLOR.TERM_TEXT_WHITE,
    k: TERM_COLOR.TERM_TEXT_OBFUSCATED,
    l: TERM_COLOR.TERM_TEXT_BOLD,
    m: TERM_COLOR.TERM_TEXT_STRIKETHROUGH,
    n: TERM_COLOR.TERM_TEXT_UNDERLINE,
    o: TERM_COLOR.TERM_TEXT_ITALIC,
    r: TERM_COLOR.TERM_RESET,
  };

  Object.entries(colorMap).forEach(([code, value]) => {
    nextText = nextText.replace(new RegExp(`§${code}`, 'gim'), value);
    nextText = nextText.replace(new RegExp(`&${code}`, 'gim'), value);
  });

  [
    [['\\d{1,3}%', 'true', 'false'], TERM_COLOR.TERM_TEXT_BLUE],
    [['information', 'info', '\\(', '\\)', '\\{', '\\}', '\\"', '&lt;', '&gt;', '-->', '->', '>>>'], TERM_COLOR.TERM_TEXT_DARK_GREEN],
    [['Error', 'Caused by', 'panic'], TERM_COLOR.TERM_TEXT_RED],
    [['WARNING', 'Warn'], TERM_COLOR.TERM_TEXT_GOLD],
  ].forEach(([patterns, color]) => {
    patterns.forEach((pattern) => {
      nextText = nextText.replace(new RegExp(`(${pattern.replace(/ /gim, '&nbsp;')})`, 'gim'), `${color}$1${TERM_COLOR.TERM_RESET}`);
    });
  });

  return nextText.replace(/\r\n/gm, `${TERM_COLOR.TERM_RESET}\r\n`);
}

export function getDockerHubLogoUrl(image = '') {
  const base = image.split('@')[0].split(':')[0];
  if (!base) return '';
  const parts = base.split('/').filter(Boolean);
  const withoutRegistry = parts[0]?.includes('.') || parts[0]?.includes(':') ? parts.slice(1) : parts;
  if (withoutRegistry.length === 0) return '';
  const namespace = withoutRegistry.length === 1 ? 'library' : withoutRegistry[0];
  const repo = withoutRegistry.length === 1 ? withoutRegistry[0] : withoutRegistry[1];
  if (!namespace || !repo || withoutRegistry.length > 2) return '';
  return `https://hub.docker.com/api/media/repos_logo/v1/${encodeURIComponent(`${namespace}/${repo}`)}`;
}
