# Beiming Daemon

Single-binary daemon for Beiming nodes.

## Build

Windows:

```powershell
npm run daemon:build:win
```

Linux amd64 from Windows PowerShell:

```powershell
cd backend/node-daemon
$env:GOOS="linux"
$env:GOARCH="amd64"
go build -o ../../bin/beiming-daemon-linux-amd64 .
```

## Run

```bash
./beiming-daemon-linux-amd64 \
  --host 0.0.0.0 \
  --port 8790 \
  --token your-token \
  --data /var/lib/beiming-daemon
```

Health check:

```bash
curl -H "Authorization: Bearer your-token" http://127.0.0.1:8790/health
```

## Data

Instances are stored in:

```text
<data>/daemon-instances.json
```

The process output buffer is kept in memory and is capped by `--output-limit` or
`BEIMING_DAEMON_OUTPUT_LIMIT`.
