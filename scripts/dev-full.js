import { spawn, spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import net from 'node:net';

const isWindows = process.platform === 'win32';
const npmCommand = 'npm';
const mvnCommand = 'mvn';
const env = { ...process.env, ...readDotEnv('.env') };

const children = [];
const dbTunnel = await maybeStartDbTunnel(env);
if (dbTunnel) {
  children.push(dbTunnel);
}

children.push(
  spawn(mvnCommand, ['spring-boot:run'], {
    cwd: 'backend/auth-service',
    stdio: 'inherit',
    shell: isWindows,
    env: { ...env, AUTH_SERVICE_PORT: env.AUTH_SERVICE_PORT || '8792' },
  }),
  spawn(mvnCommand, ['spring-boot:run'], {
    cwd: 'backend/resource-service',
    stdio: 'inherit',
    shell: isWindows,
    env: { ...env, RESOURCE_SERVICE_PORT: env.RESOURCE_SERVICE_PORT || '8791' },
  }),
  spawn(mvnCommand, ['spring-boot:run'], {
    cwd: 'backend/profile-service',
    stdio: 'inherit',
    shell: isWindows,
    env: {
      ...env,
      PROFILE_SERVICE_PORT: env.PROFILE_SERVICE_PORT || '8793',
      AUTH_SERVICE_URL: env.AUTH_SERVICE_URL || 'http://127.0.0.1:8792',
    },
  }),
  spawn(mvnCommand, ['spring-boot:run'], {
    cwd: 'backend/api-gateway',
    stdio: 'inherit',
    shell: isWindows,
    env: {
      ...env,
      API_PORT: env.API_PORT || '8787',
      RESOURCE_SERVICE_URL: env.RESOURCE_SERVICE_URL || 'http://127.0.0.1:8791',
      AUTH_SERVICE_URL: env.AUTH_SERVICE_URL || 'http://127.0.0.1:8792',
      PROFILE_SERVICE_URL: env.PROFILE_SERVICE_URL || 'http://127.0.0.1:8793',
    },
  }),
  spawn(npmCommand, ['--prefix', 'frontend', 'run', 'dev', '--', '--port', '5173'], { stdio: 'inherit', shell: isWindows, env }),
);

function readDotEnv(path) {
  if (!existsSync(path)) return {};
  const result = {};
  for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const index = trimmed.indexOf('=');
    if (index <= 0) continue;
    const key = trimmed.slice(0, index).trim();
    let value = trimmed.slice(index + 1).trim();
    if (value.length >= 2 && (value.startsWith('"') && value.endsWith('"') || value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    result[key] = value;
  }
  return result;
}

function shutdown() {
  for (const child of children) {
    if (!child.pid || child.killed) continue;
    if (isWindows) {
      spawnSync('taskkill', ['/pid', String(child.pid), '/t', '/f'], {
        stdio: 'ignore',
        windowsHide: true,
      });
    } else {
      child.kill();
    }
  }
}

async function maybeStartDbTunnel(env) {
  if (String(env.AUTH_DB_TUNNEL || '').toLowerCase() === 'false' || env.AUTH_DB_TUNNEL === '0') {
    return null;
  }

  const dbUrl = env.AUTH_DB_URL || env.DATABASE_URL || '';
  const localHost = env.AUTH_DB_TUNNEL_LOCAL_HOST || '127.0.0.1';
  const localPort = Number(env.AUTH_DB_TUNNEL_LOCAL_PORT || '15432');
  if (!dbUrl.includes(`${localHost}:${localPort}`)) {
    return null;
  }

  if (await isTcpListening(localHost, localPort)) {
    console.log(`Database tunnel already listening on ${localHost}:${localPort}`);
    return null;
  }

  const remoteHost = env.AUTH_DB_TUNNEL_REMOTE_HOST || '127.0.0.1';
  const remotePort = env.AUTH_DB_TUNNEL_REMOTE_PORT || '5432';
  const sshTarget = env.AUTH_DB_TUNNEL_SSH_TARGET || 'root@192.168.1.5';
  console.log(`Starting database tunnel ${localHost}:${localPort} -> ${sshTarget}:${remoteHost}:${remotePort}`);

  const child = spawn('ssh', ['-N', '-L', `${localHost}:${localPort}:${remoteHost}:${remotePort}`, sshTarget], {
    stdio: 'inherit',
    shell: false,
    windowsHide: true,
  });

  for (let attempt = 0; attempt < 20; attempt += 1) {
    await delay(250);
    if (await isTcpListening(localHost, localPort)) {
      return child;
    }
  }

  console.warn(`Database tunnel did not become ready on ${localHost}:${localPort}; services will still start.`);
  return child;
}

function isTcpListening(host, port) {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host, port, timeout: 500 }, () => {
      socket.destroy();
      resolve(true);
    });
    socket.on('error', () => resolve(false));
    socket.on('timeout', () => {
      socket.destroy();
      resolve(false);
    });
  });
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

process.on('SIGINT', () => {
  shutdown();
  process.exit(0);
});

process.on('SIGTERM', () => {
  shutdown();
  process.exit(0);
});
