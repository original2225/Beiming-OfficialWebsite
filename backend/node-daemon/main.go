package main

import (
	"archive/zip"
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"golang.org/x/text/encoding/simplifiedchinese"
)

const defaultOutputLimit = 240000

type InstanceConfig struct {
	ID        string            `json:"id"`
	Name      string            `json:"name"`
	Cwd       string            `json:"cwd"`
	Command   string            `json:"command"`
	Env       map[string]string `json:"env"`
	AutoStart bool              `json:"autoStart"`
}

type containerMount struct {
	Type        string `json:"Type"`
	Source      string `json:"Source"`
	Destination string `json:"Destination"`
}

type containerFileActionBody struct {
	Action        string `json:"action"`
	Path          string `json:"path"`
	Name          string `json:"name"`
	TargetPath    string `json:"targetPath"`
	ContentBase64 string `json:"contentBase64"`
	UploadID      string `json:"uploadId"`
	ChunkIndex    int    `json:"chunkIndex"`
	TotalChunks   int    `json:"totalChunks"`
	ChunkSize     int64  `json:"chunkSize"`
	Size          int64  `json:"size"`
	Encoding      string `json:"encoding"`
}

type containerCreateRequest struct {
	Name          string   `json:"name"`
	Image         string   `json:"image"`
	RestartPolicy string   `json:"restartPolicy"`
	NetworkMode   string   `json:"networkMode"`
	Ports         []string `json:"ports"`
	Env           []string `json:"env"`
	Mounts        []string `json:"mounts"`
	Privileged    bool     `json:"privileged"`
	WorkingDir    string   `json:"workingDir"`
	Command       string   `json:"command"`
	CpuLimit      float64  `json:"cpuLimit"`
	MemoryLimit   string   `json:"memoryLimit"`
	NetDownload   string   `json:"networkDownloadLimit"`
	NetUpload     string   `json:"networkUploadLimit"`
	StdinOpen     *bool    `json:"stdinOpen"`
	Tty           *bool    `json:"tty"`
}

type Instance struct {
	Config    InstanceConfig
	Output    string
	Cmd       *exec.Cmd
	Stdin     io.WriteCloser
	StartedAt int64
	StoppedAt int64
	ExitCode  *int
	Watchers  map[*websocket.Conn]bool
	mu        sync.Mutex
}

type Store struct {
	Instances map[string]*Instance
	Config    string
	Limit     int
	mu        sync.Mutex
}

type App struct {
	store      *Store
	token      string
	uploadDir  string
	downloads  map[string]map[*exec.Cmd]bool
	downloadMu sync.Mutex
	upgrader   websocket.Upgrader
}

func main() {
	host := envDefault("BEIMING_DAEMON_HOST", "127.0.0.1")
	port := envDefault("BEIMING_DAEMON_PORT", "8790")
	token := os.Getenv("BEIMING_DAEMON_TOKEN")
	dataDir := envDefault("BEIMING_DAEMON_DATA", "data")
	outputLimit := envInt("BEIMING_DAEMON_OUTPUT_LIMIT", defaultOutputLimit)

	flag.StringVar(&host, "host", host, "listen host")
	flag.StringVar(&port, "port", port, "listen port")
	flag.StringVar(&token, "token", token, "bearer token")
	flag.StringVar(&dataDir, "data", dataDir, "data directory")
	flag.IntVar(&outputLimit, "output-limit", outputLimit, "output ring buffer character limit")
	flag.Parse()

	store := &Store{
		Instances: map[string]*Instance{},
		Config:    filepath.Join(dataDir, "daemon-instances.json"),
		Limit:     outputLimit,
	}
	if err := store.Load(); err != nil {
		log.Fatalf("load instances: %v", err)
	}
	store.AutoStart()

	app := &App{
		store:     store,
		token:     token,
		uploadDir: filepath.Join(dataDir, "uploads"),
		downloads: map[string]map[*exec.Cmd]bool{},
		upgrader: websocket.Upgrader{
			CheckOrigin: func(_ *http.Request) bool { return true },
		},
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", app.withAuth(app.health))
	mux.HandleFunc("/api/instances", app.withAuth(app.instances))
	mux.HandleFunc("/api/instances/", app.withAuth(app.instanceAction))
	mux.HandleFunc("/api/metrics", app.withAuth(app.metrics))
	mux.HandleFunc("/api/containers", app.withAuth(app.containers))
	mux.HandleFunc("/api/containers/", app.withAuth(app.containerAction))
	mux.HandleFunc("/api/images", app.withAuth(app.images))
	mux.HandleFunc("/api/vms", app.withAuth(app.vms))
	mux.HandleFunc("/api/vms/", app.withAuth(app.vmAction))
	mux.HandleFunc("/ws", app.ws)

	addr := host + ":" + port
	log.Printf("Beiming daemon listening on http://%s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}

func (a *App) withAuth(next func(http.ResponseWriter, *http.Request)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		writeCORSHeaders(w)
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if a.token != "" {
			value := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
			if value == "" {
				value = r.URL.Query().Get("token")
			}
			if value != a.token {
				writeJSON(w, http.StatusUnauthorized, map[string]any{"ok": false, "message": "Unauthorized daemon request"})
				return
			}
		}
		next(w, r)
	}
}

func (a *App) health(w http.ResponseWriter, _ *http.Request) {
	writeOK(w, map[string]any{"service": "beiming-daemon", "outputLimit": a.store.Limit})
}

func (a *App) instances(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "message": "Method not allowed"})
		return
	}
	writeOK(w, a.store.List())
}

func (a *App) instanceAction(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/instances/")
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) == 0 || parts[0] == "" {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "message": "Instance not found"})
		return
	}
	id := parts[0]
	if len(parts) == 1 && r.Method == http.MethodPut {
		var cfg InstanceConfig
		if err := json.NewDecoder(r.Body).Decode(&cfg); err != nil {
			writeError(w, err)
			return
		}
		cfg.ID = id
		result, err := a.store.Upsert(cfg)
		if err != nil {
			writeError(w, err)
			return
		}
		writeOK(w, result)
		return
	}
	if len(parts) == 1 && r.Method == http.MethodDelete {
		err := a.store.Delete(id)
		if err != nil {
			writeError(w, err)
			return
		}
		writeOK(w, map[string]string{"id": id})
		return
	}
	if len(parts) != 2 {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "message": "Unsupported instance path"})
		return
	}
	switch parts[1] {
	case "start":
		inst, err := a.store.Start(id)
		if err != nil {
			writeError(w, err)
			return
		}
		writeOK(w, inst.Summary())
	case "stop":
		writeOK(w, a.store.Stop(id, false))
	case "kill":
		writeOK(w, a.store.Stop(id, true))
	case "restart":
		_ = a.store.Stop(id, false)
		time.Sleep(800 * time.Millisecond)
		inst, err := a.store.Start(id)
		if err != nil {
			writeError(w, err)
			return
		}
		writeOK(w, inst.Summary())
	case "command":
		var body struct {
			Command string `json:"command"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		err := a.store.SendCommand(id, body.Command)
		if err != nil {
			writeError(w, err)
			return
		}
		writeOK(w, map[string]bool{"sent": true})
	case "output":
		output, limit, err := a.store.Output(id)
		if err != nil {
			writeError(w, err)
			return
		}
		writeOK(w, map[string]any{"text": output, "limit": limit})
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "message": "Unsupported operation"})
	}
}

func (a *App) metrics(w http.ResponseWriter, _ *http.Request) {
	payload := collectMetrics()
	writeOK(w, payload)
}

func (a *App) containers(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		a.createContainer(w, r)
		return
	}
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "message": "Method not allowed"})
		return
	}
	ids, _ := runShell("if command -v docker >/dev/null 2>&1; then docker ps -aq; fi")
	rows, _ := runJSONLines("if command -v docker >/dev/null 2>&1; then docker ps -a --format '{{json .}}'; fi")
	stats := []map[string]any{}
	inspect := []map[string]any{}
	swap := map[string]int64{}
	if idArgs := dockerIDArgs(ids); idArgs != "" {
		inspect, _ = runJSONLines("docker inspect " + idArgs + " --format '{{json .}}' 2>/dev/null || true")
		if r.URL.Query().Get("fast") != "1" {
			stats, _ = runJSONLines("docker stats --no-stream --format '{{json .}}' " + idArgs + " 2>/dev/null || true")
			swap = collectContainerSwap(inspect)
		}
	}
	writeOK(w, map[string]any{"rows": rows, "stats": stats, "inspect": inspect, "swap": swap, "cpuThreads": runtime.NumCPU()})
}

func (a *App) containerStats(w http.ResponseWriter, _ *http.Request) {
	ids, _ := runShell("if command -v docker >/dev/null 2>&1; then docker ps -q; fi")
	stats := []map[string]any{}
	inspect := []map[string]any{}
	swap := map[string]int64{}
	if idArgs := dockerIDArgs(ids); idArgs != "" {
		stats, _ = runJSONLines("docker stats --no-stream --format '{{json .}}' " + idArgs + " 2>/dev/null || true")
		inspect, _ = runJSONLines("docker inspect " + idArgs + " --format '{{json .}}' 2>/dev/null || true")
		swap = collectContainerSwap(inspect)
	}
	writeOK(w, map[string]any{"stats": stats, "inspect": inspect, "swap": swap, "cpuThreads": runtime.NumCPU()})
}

func (a *App) createContainer(w http.ResponseWriter, r *http.Request) {
	var body containerCreateRequest
	_ = json.NewDecoder(r.Body).Decode(&body)
	args, image, errMessage := buildDockerRunArgs(body)
	if errMessage != "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": errMessage})
		return
	}
	text, err := runShell("docker image inspect " + shellQuote(image) + " >/dev/null 2>&1 || docker pull " + shellQuote(image) + " 2>&1")
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "message": strings.TrimSpace(text)})
		return
	}
	text, err = runShell(strings.Join(args, " ") + " 2>&1")
	if err != nil && text == "" {
		writeError(w, err)
		return
	}
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "message": strings.TrimSpace(text)})
		return
	}
	writeOK(w, map[string]string{"output": strings.TrimSpace(text)})
}

func buildDockerRunArgs(body containerCreateRequest) ([]string, string, string) {
	name := strings.TrimSpace(body.Name)
	image := strings.TrimSpace(body.Image)
	if image == "" {
		return nil, "", "Image is required"
	}
	args := []string{"docker run -d"}
	if name != "" {
		args = append(args, "--name "+shellQuote(name))
	}
	restart := strings.TrimSpace(body.RestartPolicy)
	if restart != "" && restart != "no" {
		if !validRestartPolicy(restart) {
			return nil, "", "Invalid restart policy"
		}
		args = append(args, "--restart "+shellQuote(restart))
	}
	networkMode := strings.TrimSpace(body.NetworkMode)
	if networkMode != "" && networkMode != "bridge" {
		args = append(args, "--network "+shellQuote(networkMode))
	}
	if networkMode != "host" {
		for _, port := range body.Ports {
			port = strings.TrimSpace(port)
			if port != "" {
				args = append(args, "-p "+shellQuote(port))
			}
		}
	}
	for _, env := range body.Env {
		env = strings.TrimSpace(env)
		if env != "" {
			args = append(args, "-e "+shellQuote(env))
		}
	}
	for _, mount := range body.Mounts {
		mount = strings.TrimSpace(mount)
		if mount != "" {
			args = append(args, "-v "+shellQuote(mount))
		}
	}
	if body.Privileged {
		args = append(args, "--privileged")
	}
	if body.CpuLimit > 0 {
		args = append(args, "--cpus "+shellQuote(fmt.Sprintf("%.3f", body.CpuLimit)))
	}
	if strings.TrimSpace(body.MemoryLimit) != "" && strings.TrimSpace(body.MemoryLimit) != "0" {
		args = append(args, "--memory "+shellQuote(strings.TrimSpace(body.MemoryLimit)))
	}
	if strings.TrimSpace(body.WorkingDir) != "" {
		args = append(args, "-w "+shellQuote(strings.TrimSpace(body.WorkingDir)))
	}
	if strings.TrimSpace(body.NetDownload) != "" {
		args = append(args, "-l "+shellQuote("beiming.net.download="+strings.TrimSpace(body.NetDownload)))
	}
	if strings.TrimSpace(body.NetUpload) != "" {
		args = append(args, "-l "+shellQuote("beiming.net.upload="+strings.TrimSpace(body.NetUpload)))
	}
	if body.StdinOpen == nil || *body.StdinOpen {
		args = append(args, "-i")
	}
	if body.Tty == nil || *body.Tty {
		args = append(args, "-t")
	}
	args = append(args, shellQuote(image))
	if strings.TrimSpace(body.Command) != "" {
		args = append(args, body.Command)
	}
	return args, image, ""
}

func (a *App) images(w http.ResponseWriter, _ *http.Request) {
	rows, _ := runJSONLines("if command -v docker >/dev/null 2>&1; then docker images --format '{{json .}}'; fi")
	writeOK(w, rows)
}

func (a *App) vms(w http.ResponseWriter, _ *http.Request) {
	text, _ := runShell("if command -v virsh >/dev/null 2>&1; then virsh list --all 2>/dev/null || true; else true; fi")
	writeOK(w, text)
}

func (a *App) vmAction(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "message": "Method not allowed"})
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/api/vms/")
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) != 2 || parts[0] == "" {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "message": "Unsupported VM path"})
		return
	}
	name := shellQuote(parts[0])
	var command string
	switch parts[1] {
	case "start":
		command = "virsh start " + name
	case "stop":
		command = "virsh shutdown " + name
	case "restart":
		command = "virsh reboot " + name
	case "kill":
		command = "virsh destroy " + name
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "message": "Unsupported VM operation"})
		return
	}
	text, err := runShell("if command -v virsh >/dev/null 2>&1; then " + command + " 2>&1; else echo 'virsh not found'; exit 127; fi")
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "message": strings.TrimSpace(text)})
		return
	}
	writeOK(w, map[string]string{"output": strings.TrimSpace(text)})
}

func (a *App) containerAction(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/containers/")
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) == 1 && parts[0] == "stats" && r.Method == http.MethodGet {
		a.containerStats(w, r)
		return
	}
	if len(parts) < 2 {
		if len(parts) == 1 && r.Method == http.MethodGet {
			a.getContainer(w, parts[0], r)
			return
		}
		if len(parts) == 1 && r.Method == http.MethodPut {
			a.updateContainer(w, parts[0], r)
			return
		}
		if len(parts) == 1 && r.Method == http.MethodDelete {
			text, err := runShell("docker rm -f " + shellQuote(parts[0]) + " 2>&1")
			if err != nil && text == "" {
				writeError(w, err)
				return
			}
			if err != nil {
				writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "message": strings.TrimSpace(text)})
				return
			}
			writeOK(w, map[string]string{"output": strings.TrimSpace(text)})
			return
		}
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "message": "Unsupported container path"})
		return
	}
	id := shellQuote(parts[0])
	switch parts[1] {
	case "files":
		if len(parts) > 2 && parts[2] == "download-cancel" {
			a.containerFileDownloadCancel(w, r)
			return
		}
		if len(parts) > 2 && parts[2] == "download" {
			a.containerFileDownload(w, parts[0], r)
			return
		}
		if len(parts) > 2 && parts[2] == "upload-cleanup" {
			a.containerFileUploadCleanup(w, r)
			return
		}
		if len(parts) > 2 && parts[2] == "upload-chunk" {
			a.containerFileUploadChunk(w, parts[0], r)
			return
		}
		a.containerFiles(w, parts[0], r)
	case "logs":
		tail := 220
		if value := r.URL.Query().Get("tail"); value != "" {
			_, _ = fmt.Sscanf(value, "%d", &tail)
		}
		if tail < 20 {
			tail = 20
		}
		if tail > 5000 {
			tail = 5000
		}
		args := []string{"docker logs", "--tail", fmt.Sprintf("%d", tail)}
		if r.URL.Query().Get("sinceStart") == "1" {
			if current, err := inspectContainer(parts[0]); err == nil {
				status := nestedString(current, "State", "Status")
				if startedAt := nestedString(current, "State", "StartedAt"); status == "running" && startedAt != "" && !strings.HasPrefix(startedAt, "0001-") {
					if parsed, err := time.Parse(time.RFC3339Nano, startedAt); err == nil {
						startedAt = parsed.Format(time.RFC3339)
					}
					args = append(args, "--since", shellQuote(startedAt))
				}
			}
		}
		args = append(args, id)
		text, err := runShell(strings.Join(args, " ") + " 2>&1")
		if err != nil {
			writeError(w, err)
			return
		}
		writeOK(w, text)
	case "exec":
		var body struct {
			Command string `json:"command"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		text, err := runShell("docker exec " + id + " sh -lc " + shellQuote(body.Command) + " 2>&1")
		if err != nil && text == "" {
			writeError(w, err)
			return
		}
		writeOK(w, map[string]string{"output": text})
	case "start", "stop", "restart", "kill":
		command := "docker " + parts[1] + " " + id
		if parts[1] == "stop" {
			command = "docker stop -t 3 " + id
		}
		if parts[1] == "restart" {
			command = "docker restart -t 3 " + id
		}
		text, err := runShell(command + " 2>&1")
		if err != nil && text == "" {
			writeError(w, err)
			return
		}
		writeOK(w, map[string]string{"output": strings.TrimSpace(text)})
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "message": "Unsupported container operation"})
	}
}

func (a *App) getContainer(w http.ResponseWriter, id string, r *http.Request) {
	safeID := shellQuote(id)
	inspectRows, _ := runJSONLines("docker inspect " + safeID + " --format '{{json .}}' 2>/dev/null || true")
	if len(inspectRows) == 0 {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "message": "Container not found"})
		return
	}
	fullID := stringValue(inspectRows[0], "Id")
	psTarget := safeID
	if fullID != "" {
		psTarget = shellQuote(fullID)
	}
	rows, _ := runJSONLines("docker ps -a --filter id=" + psTarget + " --format '{{json .}}' 2>/dev/null || true")
	statsRows := []map[string]any{}
	swapRows := map[string]int64{}
	if r.URL.Query().Get("fast") != "1" && len(inspectRows) > 0 {
		statsTarget := fullID
		if statsTarget == "" && len(rows) > 0 {
			statsTarget = stringValue(rows[0], "ID")
		}
		statsRows, _ = runJSONLines("docker stats --no-stream --format '{{json .}}' " + shellQuote(statsTarget) + " 2>/dev/null || true")
		swapRows = collectContainerSwap(inspectRows)
	}
	inspect := map[string]any{}
	if len(inspectRows) > 0 {
		inspect = inspectRows[0]
	}
	stats := map[string]any{}
	if len(statsRows) > 0 {
		stats = statsRows[0]
	}
	swap := int64(0)
	if fullID := stringValue(inspect, "Id"); fullID != "" {
		swap = swapRows[fullID]
	}
	row := map[string]any{}
	if len(rows) > 0 {
		row = rows[0]
	}
	writeOK(w, map[string]any{"row": row, "inspect": inspect, "stats": stats, "swap": swap, "cpuThreads": runtime.NumCPU()})
}

func (a *App) updateContainer(w http.ResponseWriter, containerID string, r *http.Request) {
	var body struct {
		Name          string    `json:"name"`
		Image         string    `json:"image"`
		RestartPolicy string    `json:"restartPolicy"`
		NetworkMode   string    `json:"networkMode"`
		Ports         *[]string `json:"ports"`
		Env           *[]string `json:"env"`
		Mounts        *[]string `json:"mounts"`
		Privileged    *bool     `json:"privileged"`
		WorkingDir    *string   `json:"workingDir"`
		Command       *string   `json:"command"`
		CpuLimit      float64   `json:"cpuLimit"`
		MemoryLimit   string    `json:"memoryLimit"`
		NetDownload   string    `json:"networkDownloadLimit"`
		NetUpload     string    `json:"networkUploadLimit"`
		StdinOpen     *bool     `json:"stdinOpen"`
		Tty           *bool     `json:"tty"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	current, err := inspectContainer(containerID)
	if err != nil {
		writeError(w, err)
		return
	}
	currentName := strings.TrimPrefix(mapString(current, "Name"), "/")
	currentImage := nestedString(current, "Config", "Image")
	currentNetwork := nestedString(current, "HostConfig", "NetworkMode")
	if currentNetwork == "" {
		currentNetwork = "bridge"
	}
	name := strings.TrimSpace(body.Name)
	if name == "" {
		name = currentName
	}
	image := strings.TrimSpace(body.Image)
	if image == "" {
		image = currentImage
	}
	networkMode := strings.TrimSpace(body.NetworkMode)
	if networkMode == "" {
		networkMode = currentNetwork
	}
	restartPolicy := strings.TrimSpace(body.RestartPolicy)
	if restartPolicy == "" {
		restartPolicy = nestedString(current, "HostConfig", "RestartPolicy", "Name")
	}
	if restartPolicy == "" {
		restartPolicy = "no"
	}
	if !validRestartPolicy(restartPolicy) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Unsupported restart policy"})
		return
	}
	targetPorts := containerPortSpecs(current)
	if body.Ports != nil {
		targetPorts = normalizePortSpecs(*body.Ports)
	}
	targetEnv := nestedStringSlice(current, "Config", "Env")
	if body.Env != nil {
		targetEnv = normalizeStringSpecs(*body.Env)
	}
	targetMounts := nestedStringSlice(current, "HostConfig", "Binds")
	if body.Mounts != nil {
		targetMounts = normalizeStringSpecs(*body.Mounts)
	}
	targetPrivileged := nestedBool(current, "HostConfig", "Privileged")
	if body.Privileged != nil {
		targetPrivileged = *body.Privileged
	}
	targetCommand := strings.Join(nestedStringSlice(current, "Config", "Cmd"), " ")
	if body.Command != nil {
		targetCommand = strings.TrimSpace(*body.Command)
	}
	targetWorkingDir := nestedString(current, "Config", "WorkingDir")
	if body.WorkingDir != nil {
		targetWorkingDir = strings.TrimSpace(*body.WorkingDir)
	}
	targetCpuLimit := body.CpuLimit
	if targetCpuLimit <= 0 {
		targetCpuLimit = nestedFloat(current, "HostConfig", "NanoCpus") / 1000000000
	}
	targetMemoryLimit := strings.TrimSpace(body.MemoryLimit)
	if targetMemoryLimit == "" {
		targetMemoryLimit = dockerMemoryFlag(nestedFloat(current, "HostConfig", "Memory"))
	}
	targetNetDownload := strings.TrimSpace(body.NetDownload)
	if targetNetDownload == "" {
		targetNetDownload = nestedString(current, "Config", "Labels", "beiming.net.download")
	}
	targetNetUpload := strings.TrimSpace(body.NetUpload)
	if targetNetUpload == "" {
		targetNetUpload = nestedString(current, "Config", "Labels", "beiming.net.upload")
	}
	targetStdinOpen := nestedBool(current, "Config", "OpenStdin")
	if body.StdinOpen != nil {
		targetStdinOpen = *body.StdinOpen
	}
	targetTty := nestedBool(current, "Config", "Tty")
	if body.Tty != nil {
		targetTty = *body.Tty
	}
	needsRecreate := image != currentImage ||
		networkMode != currentNetwork ||
		!sameStringSet(targetPorts, containerPortSpecs(current)) ||
		!sameStringSet(targetEnv, nestedStringSlice(current, "Config", "Env")) ||
		!sameStringSet(targetMounts, nestedStringSlice(current, "HostConfig", "Binds")) ||
		targetPrivileged != nestedBool(current, "HostConfig", "Privileged") ||
		targetWorkingDir != nestedString(current, "Config", "WorkingDir") ||
		targetCommand != strings.Join(nestedStringSlice(current, "Config", "Cmd"), " ") ||
		targetNetDownload != nestedString(current, "Config", "Labels", "beiming.net.download") ||
		targetNetUpload != nestedString(current, "Config", "Labels", "beiming.net.upload") ||
		targetStdinOpen != nestedBool(current, "Config", "OpenStdin") ||
		targetTty != nestedBool(current, "Config", "Tty")
	id := shellQuote(containerID)
	output := []string{}
	if !needsRecreate && name != "" && name != currentName {
		text, err := runShell("docker rename " + id + " " + shellQuote(name) + " 2>&1")
		if err != nil && text == "" {
			writeError(w, err)
			return
		}
		output = append(output, strings.TrimSpace(text))
	}
	if !needsRecreate && restartPolicy != "" {
		updateArgs := []string{"docker update", "--restart " + shellQuote(restartPolicy)}
		if targetCpuLimit > 0 {
			updateArgs = append(updateArgs, "--cpus "+shellQuote(fmt.Sprintf("%.3f", targetCpuLimit)))
		}
		if targetMemoryLimit != "" && targetMemoryLimit != "0" {
			updateArgs = append(updateArgs, "--memory "+shellQuote(targetMemoryLimit))
		}
		updateArgs = append(updateArgs, id)
		text, err := runShell(strings.Join(updateArgs, " ") + " 2>&1")
		if err != nil && text == "" {
			writeError(w, err)
			return
		}
		output = append(output, strings.TrimSpace(text))
	}
	if needsRecreate {
		text, err := recreateContainer(containerID, current, recreateContainerOptions{
			Name:          name,
			Image:         image,
			RestartPolicy: restartPolicy,
			NetworkMode:   networkMode,
			Ports:         targetPorts,
			Env:           targetEnv,
			Mounts:        targetMounts,
			Privileged:    targetPrivileged,
			WorkingDir:    targetWorkingDir,
			Command:       targetCommand,
			CpuLimit:      targetCpuLimit,
			MemoryLimit:   targetMemoryLimit,
			NetDownload:   targetNetDownload,
			NetUpload:     targetNetUpload,
			StdinOpen:     targetStdinOpen,
			Tty:           targetTty,
		})
		if err != nil {
			if strings.TrimSpace(text) != "" {
				writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "message": strings.TrimSpace(text)})
				return
			}
			writeError(w, err)
			return
		}
		output = append(output, strings.TrimSpace(text))
	}
	writeOK(w, map[string]string{"output": strings.TrimSpace(strings.Join(output, "\n"))})
}

func (a *App) containerFiles(w http.ResponseWriter, containerID string, r *http.Request) {
	id := shellQuote(containerID)
	switch r.Method {
	case http.MethodGet:
		path := strings.TrimSpace(r.URL.Query().Get("path"))
		if path == "" {
			path = "/"
		}
		if r.URL.Query().Get("download") == "1" {
			if hostPath, ok, _ := mountedHostPath(containerID, path); ok {
				content, err := os.ReadFile(hostPath)
				if err != nil {
					writeError(w, err)
					return
				}
				writeOK(w, map[string]string{"name": filepath.Base(path), "contentBase64": base64.StdEncoding.EncodeToString(content)})
				return
			}
			text, err := a.runContainerReadShell(containerID, "base64 "+shellQuote(path)+" | tr -d '\\n'")
			if err != nil {
				writeError(w, err)
				return
			}
			writeOK(w, map[string]string{"name": filepath.Base(path), "contentBase64": strings.TrimSpace(text)})
			return
		}
		if hostPath, ok, err := mountedHostPath(containerID, path); err != nil {
			writeError(w, err)
			return
		} else if ok {
			items, err := listHostDirectory(hostPath)
			if err != nil {
				writeError(w, err)
				return
			}
			writeOK(w, map[string]any{"path": path, "items": items, "writable": true, "mode": "mounted"})
			return
		}
		command := portableListCommand(path)
		text, err := a.runContainerReadShell(containerID, command)
		if err != nil {
			writeError(w, err)
			return
		}
		items := []map[string]any{}
		for _, line := range strings.Split(strings.TrimSpace(text), "\n") {
			if strings.TrimSpace(line) == "" {
				continue
			}
			cols := strings.Split(line, "\t")
			if len(cols) < 4 {
				continue
			}
			items = append(items, map[string]any{
				"name":     cols[0],
				"type":     cols[1],
				"size":     cols[2],
				"modified": cols[3],
			})
		}
		running, _ := containerIsRunning(containerID)
		writeOK(w, map[string]any{"path": path, "items": items, "writable": running, "mode": map[bool]string{true: "running", false: "snapshot"}[running]})
	case http.MethodPost:
		var body containerFileActionBody
		_ = json.NewDecoder(r.Body).Decode(&body)
		basePath := normalizeUploadPath(body.Path)
		if basePath == "" {
			basePath = "/"
		}
		name := strings.Trim(strings.TrimSpace(body.Name), "/")
		if name == "" && body.Action != "copy" && body.Action != "extract" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Name is required"})
			return
		}
		target := strings.TrimRight(basePath, "/") + "/" + name
		if handled := a.handleStoppedMountedFilePost(w, containerID, body, basePath, target); handled {
			return
		}
		var command string
		switch body.Action {
		case "mkdir":
			command = "mkdir -p -- " + shellQuote(target)
		case "touch":
			command = ": > " + shellQuote(target)
		case "upload":
			content, err := base64.StdEncoding.DecodeString(body.ContentBase64)
			if err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Invalid file content"})
				return
			}
			tempFile, err := os.CreateTemp("", "beiming-upload-*")
			if err != nil {
				writeError(w, err)
				return
			}
			tempPath := tempFile.Name()
			if _, err := tempFile.Write(content); err != nil {
				_ = tempFile.Close()
				_ = os.Remove(tempPath)
				writeError(w, err)
				return
			}
			_ = tempFile.Close()
			defer os.Remove(tempPath)
			parentDir := filepath.Dir(target)
			if parentDir == "." || parentDir == "" {
				parentDir = "/"
			}
			if text, err := runShell("docker exec " + id + " sh -lc " + shellQuote("mkdir -p -- "+shellQuote(parentDir)) + " 2>&1"); err != nil {
				writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "message": strings.TrimSpace(text)})
				return
			}
			text, err := runShell("docker cp " + shellQuote(tempPath) + " " + shellQuote(containerID+":"+target) + " 2>&1")
			if err != nil {
				writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "message": strings.TrimSpace(text)})
				return
			}
			writeOK(w, map[string]string{"output": strings.TrimSpace(text)})
			return
		case "uploadChunk":
			uploadID := safeUploadID(body.UploadID)
			if uploadID == "" || body.TotalChunks <= 0 || body.ChunkIndex < 0 || body.ChunkIndex >= body.TotalChunks {
				writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Invalid upload session"})
				return
			}
			content, err := base64.StdEncoding.DecodeString(body.ContentBase64)
			if err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Invalid file content"})
				return
			}
			a.writeContainerUploadChunk(w, containerID, target, uploadID, body.ChunkIndex, body.TotalChunks, body.ChunkSize, body.Size, content)
			return
		case "copy":
			source := strings.TrimSpace(body.Path)
			targetDir := strings.TrimSpace(body.TargetPath)
			if source == "" || targetDir == "" {
				writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Source and target path are required"})
				return
			}
			command = `
src=` + shellQuote(source) + `
dir=` + shellQuote(targetDir) + `
base=$(basename "$src")
stem=$base
ext=
case "$base" in
  *.*) stem=${base%.*}; ext=.${base##*.};;
esac
dest="$dir/$base"
i=1
while [ -e "$dest" ]; do
  if [ "$i" -eq 1 ]; then dest="$dir/$stem - copy$ext"; else dest="$dir/$stem - copy $i$ext"; fi
  i=$((i+1))
done
cp -a -- "$src" "$dest"`
		case "extract":
			archive := strings.TrimSpace(body.Path)
			targetDir := strings.TrimSpace(body.TargetPath)
			if archive == "" || targetDir == "" {
				writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Archive and target path are required"})
				return
			}
			if err := a.extractContainerArchive(containerID, archive, targetDir, body.Encoding); err != nil {
				writeError(w, err)
				return
			}
			writeOK(w, map[string]string{"output": ""})
			return
		default:
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Unsupported file action"})
			return
		}
		text, err := runShell("docker exec " + id + " sh -lc " + shellQuote(command) + " 2>&1")
		if err != nil {
			writeError(w, err)
			return
		}
		writeOK(w, map[string]string{"output": strings.TrimSpace(text)})
	case http.MethodPut:
		var body struct {
			Path       string `json:"path"`
			Name       string `json:"name"`
			TargetPath string `json:"targetPath"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		oldPath := strings.TrimSpace(body.Path)
		newName := strings.Trim(strings.TrimSpace(body.Name), "/")
		if oldPath == "" || newName == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Path and name are required"})
			return
		}
		targetDir := strings.TrimSpace(body.TargetPath)
		if targetDir == "" {
			targetDir = filepath.Dir(oldPath)
		}
		newPath := strings.TrimRight(targetDir, "/") + "/" + newName
		if handled := handleStoppedMountedRename(w, containerID, oldPath, newPath); handled {
			return
		}
		text, err := runShell("docker exec " + id + " sh -lc " + shellQuote("mv -- "+shellQuote(oldPath)+" "+shellQuote(newPath)) + " 2>&1")
		if err != nil {
			writeError(w, err)
			return
		}
		writeOK(w, map[string]string{"output": strings.TrimSpace(text)})
	case http.MethodDelete:
		path := strings.TrimSpace(r.URL.Query().Get("path"))
		if path == "" || path == "/" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Invalid file path"})
			return
		}
		if handled := handleStoppedMountedDelete(w, containerID, path); handled {
			return
		}
		text, err := runShell("docker exec " + id + " sh -lc " + shellQuote("rm -rf -- "+shellQuote(path)) + " 2>&1")
		if err != nil {
			writeError(w, err)
			return
		}
		writeOK(w, map[string]string{"output": strings.TrimSpace(text)})
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "message": "Method not allowed"})
	}
}

func (a *App) runContainerReadShell(containerID string, command string) (string, error) {
	running, err := containerIsRunning(containerID)
	if err != nil {
		return "", err
	}
	if running {
		return runShell("docker exec " + shellQuote(containerID) + " sh -lc " + shellQuote(command) + " 2>&1")
	}
	return runStoppedContainerSnapshotShell(containerID, command)
}

func containerIsRunning(containerID string) (bool, error) {
	text, err := runShell("docker inspect -f '{{.State.Running}}' " + shellQuote(containerID) + " 2>&1")
	if err != nil {
		return false, errors.New(strings.TrimSpace(text))
	}
	return strings.TrimSpace(text) == "true", nil
}

func runStoppedContainerSnapshotShell(containerID string, command string) (string, error) {
	imageName, err := createStoppedContainerSnapshotImage(containerID)
	if err != nil {
		return imageName, err
	}
	defer removeSnapshotImage(imageName)
	return runShell("docker run --rm --network none --entrypoint sh " + shellQuote(imageName) + " -lc " + shellQuote(command) + " 2>&1")
}

func createStoppedContainerSnapshotImage(containerID string) (string, error) {
	namePart := safeDockerName(containerID)
	if namePart == "" {
		namePart = "container"
	}
	imageName := "beiming-read-" + namePart + "-" + fmt.Sprintf("%d", time.Now().UnixNano())
	text, err := runShell("docker commit " + shellQuote(containerID) + " " + shellQuote(imageName) + " 2>&1")
	if err != nil {
		return text, err
	}
	return imageName, nil
}

func removeSnapshotImage(imageName string) {
	if strings.TrimSpace(imageName) == "" {
		return
	}
	_, _ = runShell("docker rmi -f " + shellQuote(imageName) + " >/dev/null 2>&1")
}

func portableListCommand(path string) string {
	return `
cd ` + shellQuote(path) + ` || exit 1
for f in ./* ./.[!.]* ./..?*; do
  [ -e "$f" ] || continue
  name=${f#./}
  [ "$name" = "." ] && continue
  [ "$name" = ".." ] && continue
  if [ -d "$f" ]; then
    kind=d
  elif [ -L "$f" ]; then
    kind=l
  else
    kind=f
  fi
  size=$(stat -c %s -- "$f" 2>/dev/null || stat -f %z -- "$f" 2>/dev/null || echo 0)
  modified=$(stat -c %Y -- "$f" 2>/dev/null || stat -f %m -- "$f" 2>/dev/null || echo 0)
  printf '%s\t%s\t%s\t%s\n' "$name" "$kind" "$size" "$modified"
done | sort -k2,2 -k1,1`
}

func listHostDirectory(path string) ([]map[string]any, error) {
	entries, err := os.ReadDir(path)
	if err != nil {
		return nil, err
	}
	items := make([]map[string]any, 0, len(entries))
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil {
			continue
		}
		kind := "f"
		if entry.IsDir() {
			kind = "d"
		} else if entry.Type()&os.ModeSymlink != 0 {
			kind = "l"
		}
		items = append(items, map[string]any{
			"name":     entry.Name(),
			"type":     kind,
			"size":     fmt.Sprintf("%d", info.Size()),
			"modified": fmt.Sprintf("%d", info.ModTime().Unix()),
		})
	}
	return items, nil
}

func inspectContainerMounts(containerID string) ([]containerMount, error) {
	text, err := runShell("docker inspect -f '{{json .Mounts}}' " + shellQuote(containerID) + " 2>&1")
	if err != nil {
		return nil, errors.New(strings.TrimSpace(text))
	}
	var mounts []containerMount
	if err := json.Unmarshal([]byte(strings.TrimSpace(text)), &mounts); err != nil {
		return nil, err
	}
	return mounts, nil
}

func mountedHostPath(containerID string, containerPath string) (string, bool, error) {
	mounts, err := inspectContainerMounts(containerID)
	if err != nil {
		return "", false, err
	}
	cleanPath := cleanContainerPath(containerPath)
	var selected *containerMount
	for index := range mounts {
		mount := &mounts[index]
		if mount.Source == "" || mount.Destination == "" {
			continue
		}
		dest := cleanContainerPath(mount.Destination)
		if cleanPath == dest || strings.HasPrefix(cleanPath, dest+"/") {
			if selected == nil || len(dest) > len(cleanContainerPath(selected.Destination)) {
				selected = mount
			}
		}
	}
	if selected == nil {
		return "", false, nil
	}
	dest := cleanContainerPath(selected.Destination)
	rel := strings.TrimPrefix(cleanPath, dest)
	rel = strings.TrimPrefix(rel, "/")
	hostBase := filepath.Clean(selected.Source)
	hostPath := filepath.Clean(filepath.Join(hostBase, filepath.FromSlash(rel)))
	if !hostPathInside(hostBase, hostPath) {
		return "", false, fmt.Errorf("mounted path escapes source")
	}
	return hostPath, true, nil
}

func cleanContainerPath(path string) string {
	path = strings.TrimSpace(path)
	if path == "" {
		return "/"
	}
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	return filepath.ToSlash(filepath.Clean(path))
}

func hostPathInside(base string, target string) bool {
	rel, err := filepath.Rel(filepath.Clean(base), filepath.Clean(target))
	if err != nil {
		return false
	}
	return rel == "." || (!strings.HasPrefix(rel, ".."+string(os.PathSeparator)) && rel != "..")
}

func (a *App) handleStoppedMountedFilePost(w http.ResponseWriter, containerID string, body containerFileActionBody, basePath string, target string) bool {
	running, err := containerIsRunning(containerID)
	if err != nil || running {
		return false
	}
	targetHost, targetMounted, err := mountedHostPath(containerID, target)
	if err != nil {
		writeError(w, err)
		return true
	}
	switch body.Action {
	case "mkdir":
		if !targetMounted {
			writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "message": "容器已停止，只有挂载目录支持离线新建"})
			return true
		}
		err = os.MkdirAll(targetHost, 0755)
	case "touch":
		if !targetMounted {
			writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "message": "容器已停止，只有挂载目录支持离线新建"})
			return true
		}
		if err = os.MkdirAll(filepath.Dir(targetHost), 0755); err == nil {
			var file *os.File
			file, err = os.OpenFile(targetHost, os.O_CREATE|os.O_WRONLY, 0644)
			if file != nil {
				_ = file.Close()
			}
		}
	case "upload":
		if !targetMounted {
			writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "message": "容器已停止，只有挂载目录支持离线上传"})
			return true
		}
		var content []byte
		content, err = base64.StdEncoding.DecodeString(body.ContentBase64)
		if err == nil {
			err = os.MkdirAll(filepath.Dir(targetHost), 0755)
		}
		if err == nil {
			err = os.WriteFile(targetHost, content, 0644)
		}
	case "copy":
		sourceHost, sourceMounted, mapErr := mountedHostPath(containerID, body.Path)
		targetDirHost, targetDirMounted, targetErr := mountedHostPath(containerID, body.TargetPath)
		if mapErr != nil || targetErr != nil {
			writeError(w, firstError(mapErr, targetErr))
			return true
		}
		if !sourceMounted || !targetDirMounted {
			writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "message": "容器已停止，只有挂载目录支持离线复制"})
			return true
		}
		_, err = runShell("cp -a -- " + shellQuote(sourceHost) + " " + shellQuote(uniqueCopyTarget(targetDirHost, filepath.Base(sourceHost))) + " 2>&1")
	case "extract":
		sourceHost, sourceMounted, mapErr := mountedHostPath(containerID, body.Path)
		targetDirHost, targetDirMounted, targetErr := mountedHostPath(containerID, body.TargetPath)
		if mapErr != nil || targetErr != nil {
			writeError(w, firstError(mapErr, targetErr))
			return true
		}
		if !sourceMounted || !targetDirMounted {
			writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "message": "容器已停止，只有挂载目录支持离线解压"})
			return true
		}
		err = extractHostArchive(sourceHost, targetDirHost, body.Encoding)
	case "uploadChunk":
		return false
	default:
		return false
	}
	if err != nil {
		writeError(w, err)
		return true
	}
	writeOK(w, map[string]any{"output": "", "mode": "mounted"})
	return true
}

func handleStoppedMountedRename(w http.ResponseWriter, containerID string, oldPath string, newPath string) bool {
	running, err := containerIsRunning(containerID)
	if err != nil || running {
		return false
	}
	oldHost, oldMounted, oldErr := mountedHostPath(containerID, oldPath)
	newHost, newMounted, newErr := mountedHostPath(containerID, newPath)
	if oldErr != nil || newErr != nil {
		writeError(w, firstError(oldErr, newErr))
		return true
	}
	if !oldMounted || !newMounted {
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "message": "容器已停止，只有挂载目录支持离线重命名"})
		return true
	}
	if err := os.Rename(oldHost, newHost); err != nil {
		writeError(w, err)
		return true
	}
	writeOK(w, map[string]any{"output": "", "mode": "mounted"})
	return true
}

func handleStoppedMountedDelete(w http.ResponseWriter, containerID string, path string) bool {
	running, err := containerIsRunning(containerID)
	if err != nil || running {
		return false
	}
	hostPath, mounted, mapErr := mountedHostPath(containerID, path)
	if mapErr != nil {
		writeError(w, mapErr)
		return true
	}
	if !mounted {
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "message": "容器已停止，只有挂载目录支持离线删除"})
		return true
	}
	if err := os.RemoveAll(hostPath); err != nil {
		writeError(w, err)
		return true
	}
	writeOK(w, map[string]any{"output": "", "mode": "mounted"})
	return true
}

func firstError(left error, right error) error {
	if left != nil {
		return left
	}
	return right
}

func uniqueCopyTarget(dir string, base string) string {
	target := filepath.Join(dir, base)
	if _, err := os.Stat(target); os.IsNotExist(err) {
		return target
	}
	ext := filepath.Ext(base)
	stem := strings.TrimSuffix(base, ext)
	for index := 1; index < 10000; index++ {
		var name string
		if index == 1 {
			name = stem + " - copy" + ext
		} else {
			name = fmt.Sprintf("%s - copy %d%s", stem, index, ext)
		}
		target = filepath.Join(dir, name)
		if _, err := os.Stat(target); os.IsNotExist(err) {
			return target
		}
	}
	return filepath.Join(dir, fmt.Sprintf("%s-copy-%d%s", stem, time.Now().UnixNano(), ext))
}

func (a *App) extractContainerArchive(containerID string, source string, targetDir string, encoding string) error {
	if sourceHost, sourceMounted, sourceErr := mountedHostPath(containerID, source); sourceErr != nil {
		return sourceErr
	} else if targetDirHost, targetMounted, targetErr := mountedHostPath(containerID, targetDir); targetErr != nil {
		return targetErr
	} else if sourceMounted && targetMounted {
		return extractHostArchive(sourceHost, targetDirHost, encoding)
	}

	tempRoot, err := os.MkdirTemp("", "beiming-extract-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tempRoot)

	archiveHost := filepath.Join(tempRoot, filepath.Base(source))
	extractDir := filepath.Join(tempRoot, "out")
	if text, err := runShell("docker cp " + shellQuote(containerID+":"+source) + " " + shellQuote(archiveHost) + " 2>&1"); err != nil {
		return errors.New(strings.TrimSpace(text))
	}
	if err := extractHostArchive(archiveHost, extractDir, encoding); err != nil {
		return err
	}
	if text, err := runShell("docker exec " + shellQuote(containerID) + " sh -lc " + shellQuote("mkdir -p -- "+shellQuote(targetDir)) + " 2>&1"); err != nil {
		return errors.New(strings.TrimSpace(text))
	}
	if text, err := runShell("docker cp " + shellQuote(extractDir+"/.") + " " + shellQuote(containerID+":"+strings.TrimRight(targetDir, "/")+"/") + " 2>&1"); err != nil {
		return errors.New(strings.TrimSpace(text))
	}
	return nil
}

func extractHostArchive(source string, targetDir string, encoding string) error {
	if err := os.MkdirAll(targetDir, 0755); err != nil {
		return err
	}
	lower := strings.ToLower(source)
	switch {
	case strings.HasSuffix(lower, ".zip"):
		return extractZipArchive(source, targetDir, encoding)
	case strings.HasSuffix(lower, ".tar") || strings.HasSuffix(lower, ".tgz") || strings.HasSuffix(lower, ".tar.gz") || strings.HasSuffix(lower, ".tar.bz2") || strings.HasSuffix(lower, ".tbz2") || strings.HasSuffix(lower, ".tar.xz") || strings.HasSuffix(lower, ".txz"):
		text, err := runShell("tar -xf " + shellQuote(source) + " -C " + shellQuote(targetDir) + " 2>&1")
		if err != nil {
			return errors.New(strings.TrimSpace(text))
		}
		return nil
	default:
		return errors.New("Unsupported archive type")
	}
}

func extractZipArchive(source string, targetDir string, encoding string) error {
	reader, err := zip.OpenReader(source)
	if err != nil {
		return err
	}
	defer reader.Close()

	for _, file := range reader.File {
		name, err := decodeArchiveName(file.Name, encoding)
		if err != nil {
			return err
		}
		cleanName := filepath.Clean(filepath.FromSlash(name))
		if cleanName == "." || filepath.IsAbs(cleanName) || strings.HasPrefix(cleanName, ".."+string(os.PathSeparator)) || cleanName == ".." {
			return fmt.Errorf("unsafe archive path: %s", name)
		}
		target := filepath.Join(targetDir, cleanName)
		if !hostPathInside(targetDir, target) {
			return fmt.Errorf("unsafe archive path: %s", name)
		}
		if file.FileInfo().IsDir() {
			if err := os.MkdirAll(target, file.Mode()); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
			return err
		}
		sourceFile, err := file.Open()
		if err != nil {
			return err
		}
		mode := file.Mode()
		if mode == 0 {
			mode = 0644
		}
		targetFile, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
		if err != nil {
			_ = sourceFile.Close()
			return err
		}
		_, copyErr := io.Copy(targetFile, sourceFile)
		closeErr := targetFile.Close()
		_ = sourceFile.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}

func decodeArchiveName(name string, encoding string) (string, error) {
	switch strings.ToLower(strings.TrimSpace(encoding)) {
	case "gbk", "gb18030", "gb2312":
		decoded, err := simplifiedchinese.GBK.NewDecoder().String(name)
		if err != nil {
			return "", err
		}
		return decoded, nil
	default:
		return name, nil
	}
}

func (a *App) containerFileDownload(w http.ResponseWriter, containerID string, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "message": "Method not allowed"})
		return
	}
	path := strings.TrimSpace(r.URL.Query().Get("path"))
	if path == "" || path == "/" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Invalid file path"})
		return
	}
	downloadID := safeUploadID(r.URL.Query().Get("downloadId"))
	if hostPath, ok, err := mountedHostPath(containerID, path); err != nil {
		writeError(w, err)
		return
	} else if ok {
		file, err := os.Open(hostPath)
		if err != nil {
			writeError(w, err)
			return
		}
		defer file.Close()
		info, err := file.Stat()
		if err != nil {
			writeError(w, err)
			return
		}
		if info.IsDir() {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Only files can be downloaded"})
			return
		}
		name := filepath.Base(path)
		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("Content-Type", "application/octet-stream")
		setDownloadNameHeaders(w, name)
		w.Header().Set("X-File-Size", fmt.Sprintf("%d", info.Size()))
		http.ServeContent(w, r, name, info.ModTime(), file)
		return
	}
	if running, err := containerIsRunning(containerID); err == nil && !running {
		a.containerFileDownloadFromSnapshot(w, containerID, path, r, downloadID)
		return
	}
	id := shellQuote(containerID)
	statCommand := "stat -c '%s %F' -- " + shellQuote(path)
	statText, err := runShell("docker exec " + id + " sh -lc " + shellQuote(statCommand) + " 2>&1")
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "message": strings.TrimSpace(statText)})
		return
	}
	fields := strings.Fields(strings.TrimSpace(statText))
	var size int64
	if len(fields) == 0 {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "message": "File not found"})
		return
	}
	_, _ = fmt.Sscanf(fields[0], "%d", &size)
	if size < 0 || strings.Contains(strings.TrimSpace(statText), "directory") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Only files can be downloaded"})
		return
	}
	name := filepath.Base(path)
	start, end, partial, ok := parseRangeHeader(r.Header.Get("Range"), size)
	if !ok {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", size))
		w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
		return
	}
	length := end - start + 1
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", fmt.Sprintf("%d", length))
	setDownloadNameHeaders(w, name)
	w.Header().Set("X-File-Size", fmt.Sprintf("%d", size))
	if partial {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, size))
		w.WriteHeader(http.StatusPartialContent)
	} else {
		w.WriteHeader(http.StatusOK)
	}
	if r.Method == http.MethodHead || length == 0 {
		return
	}
	downloadCommand := fmt.Sprintf("dd if=%s bs=1048576 iflag=skip_bytes,count_bytes skip=%d count=%d status=none", shellQuote(path), start, length)
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	cmd := shellCommandContext(ctx, "docker exec "+id+" sh -lc "+shellQuote(downloadCommand))
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return
	}
	stderr, _ := cmd.StderrPipe()
	if err := cmd.Start(); err != nil {
		return
	}
	if downloadID != "" {
		a.registerDownloadCommand(downloadID, cmd)
		defer a.unregisterDownloadCommand(downloadID, cmd)
	}
	done := make(chan struct{})
	go func() {
		select {
		case <-r.Context().Done():
			killCommandTree(cmd)
		case <-done:
		}
	}()
	_, copyErr := io.Copy(w, stdout)
	if copyErr != nil {
		cancel()
		killCommandTree(cmd)
	}
	errBytes, _ := io.ReadAll(stderr)
	_ = cmd.Wait()
	close(done)
	if len(errBytes) > 0 {
		log.Printf("download %s: %s", containerID, strings.TrimSpace(string(errBytes)))
	}
}

func (a *App) containerFileDownloadFromSnapshot(w http.ResponseWriter, containerID string, path string, r *http.Request, downloadID string) {
	statCommand := "stat -c '%s %F' -- " + shellQuote(path)
	statText, err := a.runContainerReadShell(containerID, statCommand)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "message": strings.TrimSpace(statText)})
		return
	}
	fields := strings.Fields(strings.TrimSpace(statText))
	var size int64
	if len(fields) == 0 {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "message": "File not found"})
		return
	}
	_, _ = fmt.Sscanf(fields[0], "%d", &size)
	if size < 0 || strings.Contains(strings.TrimSpace(statText), "directory") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Only files can be downloaded"})
		return
	}
	name := filepath.Base(path)
	start, end, partial, ok := parseRangeHeader(r.Header.Get("Range"), size)
	if !ok {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", size))
		w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
		return
	}
	length := end - start + 1
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", fmt.Sprintf("%d", length))
	setDownloadNameHeaders(w, name)
	w.Header().Set("X-File-Size", fmt.Sprintf("%d", size))
	if partial {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, size))
		w.WriteHeader(http.StatusPartialContent)
	} else {
		w.WriteHeader(http.StatusOK)
	}
	if r.Method == http.MethodHead || length == 0 {
		return
	}
	imageName, err := createStoppedContainerSnapshotImage(containerID)
	if err != nil {
		log.Printf("snapshot download %s: %s", containerID, strings.TrimSpace(imageName))
		return
	}
	defer removeSnapshotImage(imageName)
	downloadCommand := fmt.Sprintf("dd if=%s bs=1048576 iflag=skip_bytes,count_bytes skip=%d count=%d status=none", shellQuote(path), start, length)
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	cmd := shellCommandContext(ctx, "docker run --rm --network none --entrypoint sh "+shellQuote(imageName)+" -lc "+shellQuote(downloadCommand))
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return
	}
	stderr, _ := cmd.StderrPipe()
	if err := cmd.Start(); err != nil {
		return
	}
	if downloadID != "" {
		a.registerDownloadCommand(downloadID, cmd)
		defer a.unregisterDownloadCommand(downloadID, cmd)
	}
	done := make(chan struct{})
	go func() {
		select {
		case <-r.Context().Done():
			killCommandTree(cmd)
		case <-done:
		}
	}()
	_, copyErr := io.Copy(w, stdout)
	if copyErr != nil {
		cancel()
		killCommandTree(cmd)
	}
	errBytes, _ := io.ReadAll(stderr)
	_ = cmd.Wait()
	close(done)
	if len(errBytes) > 0 {
		log.Printf("snapshot download %s: %s", containerID, strings.TrimSpace(string(errBytes)))
	}
}

func (a *App) containerFileDownloadCancel(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "message": "Method not allowed"})
		return
	}
	var body struct {
		DownloadID string `json:"downloadId"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	killed := a.cancelDownloadCommands(safeUploadID(body.DownloadID))
	writeOK(w, map[string]any{"killed": killed})
}

func (a *App) registerDownloadCommand(downloadID string, cmd *exec.Cmd) {
	a.downloadMu.Lock()
	defer a.downloadMu.Unlock()
	if a.downloads[downloadID] == nil {
		a.downloads[downloadID] = map[*exec.Cmd]bool{}
	}
	a.downloads[downloadID][cmd] = true
}

func (a *App) unregisterDownloadCommand(downloadID string, cmd *exec.Cmd) {
	a.downloadMu.Lock()
	defer a.downloadMu.Unlock()
	if a.downloads[downloadID] == nil {
		return
	}
	delete(a.downloads[downloadID], cmd)
	if len(a.downloads[downloadID]) == 0 {
		delete(a.downloads, downloadID)
	}
}

func (a *App) cancelDownloadCommands(downloadID string) int {
	if downloadID == "" {
		return 0
	}
	a.downloadMu.Lock()
	commands := make([]*exec.Cmd, 0, len(a.downloads[downloadID]))
	for cmd := range a.downloads[downloadID] {
		commands = append(commands, cmd)
	}
	delete(a.downloads, downloadID)
	a.downloadMu.Unlock()
	for _, cmd := range commands {
		killCommandTree(cmd)
	}
	return len(commands)
}

func (a *App) containerFileUploadChunk(w http.ResponseWriter, containerID string, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "message": "Method not allowed"})
		return
	}
	query := r.URL.Query()
	basePath := normalizeUploadPath(query.Get("path"))
	if basePath == "" {
		basePath = "/"
	}
	name := strings.Trim(strings.TrimSpace(query.Get("name")), "/")
	uploadID := safeUploadID(query.Get("uploadId"))
	var chunkIndex, totalChunks int
	var chunkSize, size int64
	_, _ = fmt.Sscanf(query.Get("chunkIndex"), "%d", &chunkIndex)
	_, _ = fmt.Sscanf(query.Get("totalChunks"), "%d", &totalChunks)
	_, _ = fmt.Sscanf(query.Get("chunkSize"), "%d", &chunkSize)
	_, _ = fmt.Sscanf(query.Get("size"), "%d", &size)
	if name == "" || uploadID == "" || totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Invalid upload chunk"})
		return
	}
	content, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 64<<20))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Invalid upload content"})
		return
	}
	target := strings.TrimRight(basePath, "/") + "/" + name
	a.writeContainerUploadChunk(w, containerID, target, uploadID, chunkIndex, totalChunks, chunkSize, size, content)
}

func normalizeUploadPath(value string) string {
	path := strings.TrimSpace(value)
	for i := 0; i < 3 && strings.Contains(path, "%"); i++ {
		decoded, err := url.QueryUnescape(path)
		if err != nil || decoded == path {
			break
		}
		path = strings.TrimSpace(decoded)
	}
	path = strings.ReplaceAll(path, "\\", "/")
	if path == "" || path == "." {
		return "/"
	}
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	return path
}

func (a *App) containerFileUploadCleanup(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "message": "Method not allowed"})
		return
	}
	var body struct {
		UploadIDs []string `json:"uploadIds"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	cleaned := 0
	for _, value := range body.UploadIDs {
		uploadID := safeUploadID(value)
		if uploadID == "" {
			continue
		}
		if err := os.RemoveAll(filepath.Join(a.uploadDir, uploadID)); err == nil {
			cleaned++
		}
	}
	writeOK(w, map[string]any{"cleaned": cleaned})
}

func (a *App) writeContainerUploadChunk(w http.ResponseWriter, containerID string, target string, uploadID string, chunkIndex int, totalChunks int, chunkSize int64, size int64, content []byte) {
	if chunkSize <= 0 {
		chunkSize = int64(len(content))
	}
	sessionDir := filepath.Join(a.uploadDir, uploadID)
	if err := os.MkdirAll(sessionDir, 0700); err != nil {
		writeError(w, err)
		return
	}
	tempPath := filepath.Join(sessionDir, "upload.part")
	tempFile, err := os.OpenFile(tempPath, os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		writeError(w, err)
		return
	}
	_, err = tempFile.WriteAt(content, int64(chunkIndex)*chunkSize)
	_ = tempFile.Close()
	if err != nil {
		writeError(w, err)
		return
	}
	markerPath := filepath.Join(sessionDir, fmt.Sprintf("%06d.done", chunkIndex))
	if err := os.WriteFile(markerPath, []byte("1"), 0600); err != nil {
		writeError(w, err)
		return
	}
	markers, err := filepath.Glob(filepath.Join(sessionDir, "*.done"))
	if err != nil || len(markers) < totalChunks {
		writeOK(w, map[string]any{"received": chunkIndex + 1, "total": totalChunks, "complete": false})
		return
	}
	lockFile, err := os.OpenFile(filepath.Join(sessionDir, ".complete"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		writeOK(w, map[string]any{"received": chunkIndex + 1, "total": totalChunks, "complete": false})
		return
	}
	_ = lockFile.Close()
	if size > 0 {
		if stat, err := os.Stat(tempPath); err == nil && stat.Size() != size {
			_ = os.RemoveAll(sessionDir)
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "message": "Uploaded file size mismatch"})
			return
		}
	}
	parentDir := filepath.Dir(target)
	if parentDir == "." || parentDir == "" {
		parentDir = "/"
	}
	if running, err := containerIsRunning(containerID); err == nil && !running {
		if hostTarget, ok, err := mountedHostPath(containerID, target); err != nil {
			_ = os.RemoveAll(sessionDir)
			writeError(w, err)
			return
		} else if ok {
			if err := os.MkdirAll(filepath.Dir(hostTarget), 0755); err != nil {
				_ = os.RemoveAll(sessionDir)
				writeError(w, err)
				return
			}
			if err := os.Rename(tempPath, hostTarget); err != nil {
				_ = os.RemoveAll(sessionDir)
				writeError(w, err)
				return
			}
			_ = os.RemoveAll(sessionDir)
			writeOK(w, map[string]any{"output": "", "complete": true, "mode": "mounted"})
			return
		}
		_ = os.RemoveAll(sessionDir)
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "message": "容器已停止，只有挂载目录支持离线上传"})
		return
	}
	id := shellQuote(containerID)
	if text, err := runShell("docker exec " + id + " sh -lc " + shellQuote("mkdir -p -- "+shellQuote(parentDir)) + " 2>&1"); err != nil {
		_ = os.RemoveAll(sessionDir)
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "message": strings.TrimSpace(text)})
		return
	}
	text, err := runShell("docker cp " + shellQuote(tempPath) + " " + shellQuote(containerID+":"+target) + " 2>&1")
	_ = os.RemoveAll(sessionDir)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "message": strings.TrimSpace(text)})
		return
	}
	writeOK(w, map[string]any{"output": strings.TrimSpace(text), "complete": true})
}

type recreateContainerOptions struct {
	Name          string
	Image         string
	RestartPolicy string
	NetworkMode   string
	Ports         []string
	Env           []string
	Mounts        []string
	Privileged    bool
	WorkingDir    string
	Command       string
	CpuLimit      float64
	MemoryLimit   string
	NetDownload   string
	NetUpload     string
	StdinOpen     bool
	Tty           bool
}

func inspectContainer(containerID string) (map[string]any, error) {
	rows, err := runJSONLines("docker inspect " + shellQuote(containerID) + " --format '{{json .}}' 2>/dev/null")
	if err != nil {
		return nil, err
	}
	if len(rows) == 0 {
		return nil, errors.New("container not found")
	}
	return rows[0], nil
}

func recreateContainer(containerID string, current map[string]any, options recreateContainerOptions) (string, error) {
	wasRunning := nestedBool(current, "State", "Running")
	currentName := strings.TrimPrefix(mapString(current, "Name"), "/")
	backupName := fmt.Sprintf("%s.beiming-backup-%d", currentName, time.Now().Unix())
	finalName := strings.TrimSpace(options.Name)
	output := []string{}
	if options.Image == "" {
		options.Image = nestedString(current, "Config", "Image")
	}
	if finalName == "" {
		finalName = currentName
	}
	options.Name = backupName
	if options.NetworkMode == "" {
		options.NetworkMode = nestedString(current, "HostConfig", "NetworkMode")
	}
	if options.RestartPolicy == "" {
		options.RestartPolicy = nestedString(current, "HostConfig", "RestartPolicy", "Name")
	}
	if options.RestartPolicy == "" {
		options.RestartPolicy = "no"
	}
	if options.Env == nil {
		options.Env = nestedStringSlice(current, "Config", "Env")
	}
	if options.Mounts == nil {
		options.Mounts = nestedStringSlice(current, "HostConfig", "Binds")
	}
	if text, err := runShell("docker image inspect " + shellQuote(options.Image) + " >/dev/null 2>&1 || docker pull " + shellQuote(options.Image) + " 2>&1"); err != nil {
		return text, err
	}
	if finalName != currentName {
		if text, err := runShell("docker inspect " + shellQuote(finalName) + " >/dev/null 2>&1"); err == nil {
			return text, fmt.Errorf("container name %s is already in use", finalName)
		}
	}
	if wasRunning {
		text, err := runShell("docker stop -t 3 " + shellQuote(containerID) + " 2>&1")
		output = append(output, strings.TrimSpace(text))
		if err != nil && text == "" {
			return strings.Join(output, "\n"), err
		}
	}
	createCmd := buildDockerCreateCommand(current, options)
	text, err := runShell(createCmd + " 2>&1")
	output = append(output, strings.TrimSpace(text))
	if err != nil {
		if wasRunning {
			_, _ = runShell("docker start " + shellQuote(currentName) + " >/dev/null 2>&1 || true")
		}
		return strings.Join(output, "\n"), err
	}
	text, err = runShell("docker rename " + shellQuote(containerID) + " " + shellQuote(backupName) + ".old 2>&1")
	output = append(output, strings.TrimSpace(text))
	if err != nil {
		_, _ = runShell("docker rm -f " + shellQuote(backupName) + " >/dev/null 2>&1 || true")
		if wasRunning {
			_, _ = runShell("docker start " + shellQuote(currentName) + " >/dev/null 2>&1 || true")
		}
		return strings.Join(output, "\n"), err
	}
	oldBackupName := backupName + ".old"
	text, err = runShell("docker rename " + shellQuote(backupName) + " " + shellQuote(finalName) + " 2>&1")
	output = append(output, strings.TrimSpace(text))
	if err != nil {
		_, _ = runShell("docker rm -f " + shellQuote(backupName) + " >/dev/null 2>&1 || true")
		_, _ = runShell("docker rename " + shellQuote(oldBackupName) + " " + shellQuote(currentName) + " >/dev/null 2>&1 || true")
		if wasRunning {
			_, _ = runShell("docker start " + shellQuote(currentName) + " >/dev/null 2>&1 || true")
		}
		return strings.Join(output, "\n"), err
	}
	if wasRunning {
		text, err = runShell("docker start " + shellQuote(finalName) + " 2>&1")
		output = append(output, strings.TrimSpace(text))
		if err != nil && text == "" {
			_, _ = runShell("docker rm -f " + shellQuote(finalName) + " >/dev/null 2>&1 || true")
			_, _ = runShell("docker rename " + shellQuote(oldBackupName) + " " + shellQuote(currentName) + " >/dev/null 2>&1 || true")
			_, _ = runShell("docker start " + shellQuote(currentName) + " >/dev/null 2>&1 || true")
			return strings.Join(output, "\n"), err
		}
	}
	text, err = runShell("docker rm -f " + shellQuote(oldBackupName) + " 2>&1")
	output = append(output, strings.TrimSpace(text))
	if err != nil && text == "" {
		return strings.Join(output, "\n"), err
	}
	return strings.Join(output, "\n"), nil
}

func buildDockerCreateCommand(current map[string]any, options recreateContainerOptions) string {
	args := []string{"docker create", "--name " + shellQuote(options.Name)}
	if options.RestartPolicy != "" && options.RestartPolicy != "no" {
		args = append(args, "--restart "+shellQuote(options.RestartPolicy))
	}
	if options.NetworkMode != "" && options.NetworkMode != "bridge" {
		args = append(args, "--network "+shellQuote(options.NetworkMode))
	}
	if options.NetworkMode != "host" {
		for _, port := range options.Ports {
			if strings.TrimSpace(port) != "" {
				args = append(args, "-p "+shellQuote(strings.TrimSpace(port)))
			}
		}
	}
	if options.Privileged {
		args = append(args, "--privileged")
	}
	if options.StdinOpen {
		args = append(args, "-i")
	}
	if options.Tty {
		args = append(args, "-t")
	}
	if options.CpuLimit > 0 {
		args = append(args, "--cpus "+shellQuote(fmt.Sprintf("%.3f", options.CpuLimit)))
	}
	if options.MemoryLimit != "" && options.MemoryLimit != "0" {
		args = append(args, "--memory "+shellQuote(options.MemoryLimit))
	}
	for _, env := range options.Env {
		args = append(args, "-e "+shellQuote(env))
	}
	for key, value := range nestedStringMap(current, "Config", "Labels") {
		if key != "" && value != "" {
			args = append(args, "-l "+shellQuote(key+"="+value))
		}
	}
	if options.NetDownload != "" {
		args = append(args, "-l "+shellQuote("beiming.net.download="+options.NetDownload))
	}
	if options.NetUpload != "" {
		args = append(args, "-l "+shellQuote("beiming.net.upload="+options.NetUpload))
	}
	for _, bind := range options.Mounts {
		args = append(args, "-v "+shellQuote(bind))
	}
	if options.WorkingDir != "" {
		args = append(args, "-w "+shellQuote(options.WorkingDir))
	}
	if user := nestedString(current, "Config", "User"); user != "" {
		args = append(args, "-u "+shellQuote(user))
	}
	entrypoint := nestedStringSlice(current, "Config", "Entrypoint")
	if len(entrypoint) > 0 && strings.TrimSpace(entrypoint[0]) != "" {
		args = append(args, "--entrypoint "+shellQuote(entrypoint[0]))
	}
	args = append(args, shellQuote(options.Image))
	if len(entrypoint) > 1 {
		for _, entryArg := range entrypoint[1:] {
			if strings.TrimSpace(entryArg) != "" {
				args = append(args, shellQuote(entryArg))
			}
		}
	}
	if strings.TrimSpace(options.Command) != "" {
		args = append(args, options.Command)
	}
	return strings.Join(args, " ")
}

func validRestartPolicy(policy string) bool {
	return map[string]bool{
		"no":             true,
		"always":         true,
		"unless-stopped": true,
		"on-failure":     true,
	}[policy]
}

func normalizePortSpecs(ports []string) []string {
	return normalizeStringSpecs(ports)
}

func normalizeStringSpecs(values []string) []string {
	result := []string{}
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value != "" {
			result = append(result, value)
		}
	}
	return result
}

func sameStringSet(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	counts := map[string]int{}
	for _, item := range left {
		counts[item]++
	}
	for _, item := range right {
		counts[item]--
		if counts[item] < 0 {
			return false
		}
	}
	return true
}

func containerPortSpecs(current map[string]any) []string {
	ports, ok := nestedMap(current, "NetworkSettings", "Ports")
	if !ok {
		return []string{}
	}
	result := []string{}
	for containerPort, bindingsAny := range ports {
		bindings, ok := bindingsAny.([]any)
		if !ok || len(bindings) == 0 {
			continue
		}
		portPart := strings.Split(containerPort, "/")
		target := portPart[0]
		protocol := "tcp"
		if len(portPart) > 1 && portPart[1] != "" {
			protocol = portPart[1]
		}
		for _, bindingAny := range bindings {
			binding, ok := bindingAny.(map[string]any)
			if !ok {
				continue
			}
			hostPort := mapString(binding, "HostPort")
			if hostPort == "" || target == "" {
				continue
			}
			spec := hostPort + ":" + target
			if protocol != "tcp" {
				spec += "/" + protocol
			}
			result = append(result, spec)
		}
	}
	return result
}

func nestedMap(root map[string]any, keys ...string) (map[string]any, bool) {
	current := root
	for index, key := range keys {
		value, ok := current[key]
		if !ok {
			return nil, false
		}
		next, ok := value.(map[string]any)
		if !ok {
			return nil, false
		}
		if index == len(keys)-1 {
			return next, true
		}
		current = next
	}
	return current, true
}

func nestedString(root map[string]any, keys ...string) string {
	if len(keys) == 0 {
		return ""
	}
	if len(keys) == 1 {
		return mapString(root, keys[0])
	}
	parent, ok := nestedMap(root, keys[:len(keys)-1]...)
	if !ok {
		return ""
	}
	return mapString(parent, keys[len(keys)-1])
}

func nestedBool(root map[string]any, keys ...string) bool {
	if len(keys) == 0 {
		return false
	}
	parent, ok := nestedMap(root, keys[:len(keys)-1]...)
	if !ok {
		return false
	}
	value, _ := parent[keys[len(keys)-1]].(bool)
	return value
}

func nestedFloat(root map[string]any, keys ...string) float64 {
	if len(keys) == 0 {
		return 0
	}
	parent, ok := nestedMap(root, keys[:len(keys)-1]...)
	if !ok {
		return 0
	}
	switch value := parent[keys[len(keys)-1]].(type) {
	case float64:
		return value
	case int:
		return float64(value)
	case int64:
		return float64(value)
	default:
		return 0
	}
}

func nestedStringSlice(root map[string]any, keys ...string) []string {
	parent, ok := nestedMap(root, keys[:len(keys)-1]...)
	if !ok {
		return []string{}
	}
	values, ok := parent[keys[len(keys)-1]].([]any)
	if !ok {
		return []string{}
	}
	result := []string{}
	for _, value := range values {
		if text, ok := value.(string); ok && text != "" {
			result = append(result, text)
		}
	}
	return result
}

func nestedStringMap(root map[string]any, keys ...string) map[string]string {
	parent, ok := nestedMap(root, keys[:len(keys)-1]...)
	if !ok {
		return map[string]string{}
	}
	values, ok := parent[keys[len(keys)-1]].(map[string]any)
	if !ok {
		return map[string]string{}
	}
	result := map[string]string{}
	for key, value := range values {
		if text, ok := value.(string); ok {
			result[key] = text
		}
	}
	return result
}

func dockerMemoryFlag(bytes float64) string {
	if bytes <= 0 {
		return ""
	}
	if bytes >= 1024*1024*1024 {
		return fmt.Sprintf("%.2fg", bytes/(1024*1024*1024))
	}
	return fmt.Sprintf("%.0fm", bytes/(1024*1024))
}

func mapString(values map[string]any, key string) string {
	if value, ok := values[key].(string); ok {
		return value
	}
	return ""
}

func dockerIDArgs(ids string) string {
	quoted := []string{}
	for _, id := range strings.Fields(ids) {
		quoted = append(quoted, shellQuote(id))
	}
	return strings.Join(quoted, " ")
}

func collectContainerSwap(inspects []map[string]any) map[string]int64 {
	result := map[string]int64{}
	for _, item := range inspects {
		id := mapString(item, "Id")
		if id == "" {
			continue
		}
		paths := []string{
			"/sys/fs/cgroup/system.slice/docker-" + id + ".scope/memory.swap.current",
			"/sys/fs/cgroup/docker/" + id + "/memory.swap.current",
			"/sys/fs/cgroup/memory/docker/" + id + "/memory.memsw.usage_in_bytes",
		}
		memoryCurrent := readInt64File("/sys/fs/cgroup/system.slice/docker-" + id + ".scope/memory.current")
		if memoryCurrent == 0 {
			memoryCurrent = readInt64File("/sys/fs/cgroup/docker/" + id + "/memory.current")
		}
		for _, path := range paths {
			value := readInt64File(path)
			if value == 0 {
				continue
			}
			if strings.HasSuffix(path, "memory.memsw.usage_in_bytes") && memoryCurrent > 0 {
				value = value - memoryCurrent
			}
			if value < 0 {
				value = 0
			}
			result[id[:12]] = value
			break
		}
	}
	return result
}

func readInt64File(path string) int64 {
	bytes, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	var value int64
	_, _ = fmt.Sscanf(strings.TrimSpace(string(bytes)), "%d", &value)
	return value
}

func (a *App) ws(w http.ResponseWriter, r *http.Request) {
	if a.token != "" && r.URL.Query().Get("token") != a.token {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"ok": false, "message": "Unauthorized daemon socket"})
		return
	}
	conn, err := a.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer conn.Close()

	var watched string
	for {
		var msg map[string]any
		if err := conn.ReadJSON(&msg); err != nil {
			if watched != "" {
				a.store.RemoveWatcher(watched, conn)
			}
			return
		}
		event, _ := msg["event"].(string)
		data, _ := msg["data"].(map[string]any)
		switch event {
		case "stream/auth":
			id, _ := data["instanceId"].(string)
			if id == "" {
				id, _ = msg["instanceId"].(string)
			}
			watched = id
			a.store.AddWatcher(id, conn)
			output, limit, err := a.store.Output(id)
			if err != nil {
				writeWSEvent(conn, "stream/auth", map[string]any{"ok": false, "message": err.Error()})
				continue
			}
			writeWSEvent(conn, "stream/auth", map[string]any{"ok": true, "data": true})
			writeWSEvent(conn, "instance/stdout", map[string]any{"ok": true, "data": map[string]any{"text": output, "limit": limit}, "timestamp": now()})
		case "stream/input":
			id, _ := data["instanceId"].(string)
			command, _ := data["command"].(string)
			if err := a.store.SendCommand(id, command); err != nil {
				writeWSEvent(conn, "instance/stdout", stdoutPacket("\r\n"+err.Error()+"\r\n"))
			}
		case "stream/write":
			id, _ := data["instanceId"].(string)
			input, _ := data["input"].(string)
			if err := a.store.WriteInput(id, input); err != nil {
				writeWSEvent(conn, "instance/stdout", stdoutPacket("\r\n"+err.Error()+"\r\n"))
			}
		case "container/attach":
			id, _ := data["containerId"].(string)
			if id == "" {
				writeWSEvent(conn, "container/attach", map[string]any{"ok": false, "message": "containerId is required"})
				continue
			}
			a.attachContainer(conn, id)
			return
		case "container/create":
			raw, _ := json.Marshal(data)
			var body containerCreateRequest
			if err := json.Unmarshal(raw, &body); err != nil {
				writeWSEvent(conn, "container/create/error", map[string]any{"ok": false, "message": err.Error(), "timestamp": now()})
				continue
			}
			go a.createContainerStream(conn, body)
		}
	}
}

func (a *App) createContainerStream(conn *websocket.Conn, body containerCreateRequest) {
	writeMu := sync.Mutex{}
	emit := func(event string, payload any) {
		writeMu.Lock()
		defer writeMu.Unlock()
		writeWSEvent(conn, event, payload)
	}
	args, image, errMessage := buildDockerRunArgs(body)
	if errMessage != "" {
		emit("container/create/error", map[string]any{"ok": false, "message": errMessage, "timestamp": now()})
		return
	}
	emit("container/create/progress", map[string]any{"stage": "prepare", "status": "检查镜像", "progress": 5, "timestamp": now()})
	if _, err := runShell("docker image inspect " + shellQuote(image) + " >/dev/null 2>&1"); err != nil {
		if err := streamDockerPull(image, emit); err != nil {
			emit("container/create/error", map[string]any{"ok": false, "message": err.Error(), "timestamp": now()})
			return
		}
	} else {
		emit("container/create/progress", map[string]any{"stage": "pull", "status": "镜像已存在", "progress": 55, "timestamp": now()})
	}
	emit("container/create/progress", map[string]any{"stage": "create", "status": "创建并启动容器", "progress": 78, "timestamp": now()})
	text, err := runShell(strings.Join(args, " ") + " 2>&1")
	if err != nil {
		if strings.TrimSpace(text) == "" {
			text = err.Error()
		}
		emit("container/create/error", map[string]any{"ok": false, "message": strings.TrimSpace(text), "timestamp": now()})
		return
	}
	emit("container/create/progress", map[string]any{"stage": "created", "status": "容器已创建", "progress": 100, "output": strings.TrimSpace(text), "timestamp": now()})
	emit("container/create/done", map[string]any{"ok": true, "output": strings.TrimSpace(text), "container": strings.TrimSpace(text), "timestamp": now()})
}

func streamDockerPull(image string, emit func(string, any)) error {
	cmd := shellCommand("docker pull " + shellQuote(image) + " 2>&1")
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	type layerState struct {
		current int64
		total   int64
		status  string
	}
	layers := map[string]layerState{}
	scanner := bufio.NewScanner(stdout)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		var event map[string]any
		if err := json.Unmarshal([]byte(line), &event); err != nil {
			progress := 30
			lower := strings.ToLower(line)
			if strings.Contains(lower, "pulling fs layer") {
				progress = 22
			} else if strings.Contains(lower, "download complete") {
				progress = 45
			} else if strings.Contains(lower, "pull complete") {
				progress = 62
			} else if strings.Contains(lower, "downloaded newer image") || strings.Contains(lower, "image is up to date") {
				progress = 70
			}
			emit("container/create/progress", map[string]any{"stage": "pull", "status": line, "progress": progress, "timestamp": now()})
			continue
		}
		id, _ := event["id"].(string)
		status, _ := event["status"].(string)
		if id == "" {
			id = status
		}
		state := layers[id]
		state.status = status
		if detail, ok := event["progressDetail"].(map[string]any); ok {
			state.current = int64(numberFromAny(detail["current"]))
			state.total = int64(numberFromAny(detail["total"]))
		}
		if state.total == 0 && (strings.Contains(strings.ToLower(status), "complete") || strings.Contains(strings.ToLower(status), "already exists")) {
			state.current = 1
			state.total = 1
		}
		layers[id] = state
		var current int64
		var total int64
		for _, layer := range layers {
			current += layer.current
			total += layer.total
		}
		progress := 15
		if total > 0 {
			progress = 10 + int(float64(current)/float64(total)*55)
			if progress > 68 {
				progress = 68
			}
		}
		emit("container/create/progress", map[string]any{
			"stage":     "pull",
			"status":    status,
			"layer":     id,
			"progress":  progress,
			"layers":    len(layers),
			"timestamp": now(),
		})
	}
	if err := scanner.Err(); err != nil {
		_ = cmd.Wait()
		return err
	}
	if err := cmd.Wait(); err != nil {
		return err
	}
	emit("container/create/progress", map[string]any{"stage": "pull", "status": "镜像拉取完成", "progress": 70, "timestamp": now()})
	return nil
}

func (a *App) attachContainer(conn *websocket.Conn, containerID string) {
	current, err := inspectContainer(containerID)
	if err != nil {
		writeWSEvent(conn, "container/attach", map[string]any{"ok": false, "message": err.Error()})
		return
	}
	if !nestedBool(current, "State", "Running") {
		writeWSEvent(conn, "container/attach", map[string]any{"ok": false, "message": "container is not running", "data": containerAttachMeta(current)})
		return
	}
	if nestedBool(current, "Config", "Tty") {
		a.attachTTYContainer(conn, containerID, current)
		return
	}
	a.attachPipeContainer(conn, containerID, current)
}

func containerAttachMeta(current map[string]any) map[string]any {
	status := nestedString(current, "State", "Status")
	startedAt := nestedString(current, "State", "StartedAt")
	sessionID := nestedString(current, "Id")
	if status == "running" && startedAt != "" && !strings.HasPrefix(startedAt, "0001-") {
		sessionID = startedAt
	}
	return map[string]any{
		"id":          nestedString(current, "Id"),
		"status":      status,
		"startedAt":   startedAt,
		"sessionId":   sessionID,
		"interactive": nestedBool(current, "Config", "OpenStdin") || nestedBool(current, "Config", "Tty") || nestedBool(current, "Config", "AttachStdin"),
		"tty":         nestedBool(current, "Config", "Tty"),
		"stdinOpen":   nestedBool(current, "Config", "OpenStdin"),
	}
}

func (a *App) attachPipeContainer(conn *websocket.Conn, containerID string, current map[string]any) {
	cmd := shellCommand("docker attach --sig-proxy=false " + shellQuote(containerID))
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		writeWSEvent(conn, "container/attach", map[string]any{"ok": false, "message": err.Error()})
		return
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		writeWSEvent(conn, "container/attach", map[string]any{"ok": false, "message": err.Error()})
		return
	}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		writeWSEvent(conn, "container/attach", map[string]any{"ok": false, "message": err.Error()})
		return
	}
	if err := cmd.Start(); err != nil {
		writeWSEvent(conn, "container/attach", map[string]any{"ok": false, "message": err.Error()})
		return
	}
	writeMu := sync.Mutex{}
	writeSafe := func(event string, payload any) {
		writeMu.Lock()
		defer writeMu.Unlock()
		writeWSEvent(conn, event, payload)
	}
	writeWSEvent(conn, "container/attach", map[string]any{"ok": true, "data": containerAttachMeta(current)})
	done := make(chan struct{})
	pipeOutput := func(reader io.Reader) {
		buffer := make([]byte, 4096)
		for {
			n, err := reader.Read(buffer)
			if n > 0 {
				writeSafe("container/stdout", stdoutPacket(string(buffer[:n])))
			}
			if err != nil {
				return
			}
		}
	}
	go pipeOutput(stdout)
	go pipeOutput(stderr)
	go func() {
		_ = cmd.Wait()
		close(done)
	}()
	inputCh := make(chan string, 16)
	connDone := make(chan struct{})
	go func() {
		defer close(connDone)
		for {
			var msg map[string]any
			if err := conn.ReadJSON(&msg); err != nil {
				return
			}
			event, _ := msg["event"].(string)
			data, _ := msg["data"].(map[string]any)
			if event != "container/input" {
				continue
			}
			input, _ := data["input"].(string)
			select {
			case inputCh <- input:
			case <-done:
				return
			}
		}
	}()
	for {
		select {
		case <-done:
			writeSafe("container/closed", map[string]any{"ok": true, "timestamp": now()})
			return
		case input := <-inputCh:
			_, _ = io.WriteString(stdin, input)
		case <-connDone:
			_ = stdin.Close()
			if cmd.Process != nil {
				_ = cmd.Process.Kill()
			}
			return
		}
	}
}

func (a *App) attachTTYContainer(conn *websocket.Conn, containerID string, current map[string]any) {
	dockerConn, dockerReader, err := openDockerAttach(containerID)
	if err != nil {
		writeWSEvent(conn, "container/attach", map[string]any{"ok": false, "message": err.Error(), "data": containerAttachMeta(current)})
		return
	}
	defer dockerConn.Close()
	writeMu := sync.Mutex{}
	writeSafe := func(event string, payload any) {
		writeMu.Lock()
		defer writeMu.Unlock()
		writeWSEvent(conn, event, payload)
	}
	writeWSEvent(conn, "container/attach", map[string]any{"ok": true, "data": containerAttachMeta(current)})
	done := make(chan struct{})
	go func() {
		buffer := make([]byte, 4096)
		for {
			n, err := dockerReader.Read(buffer)
			if n > 0 {
				writeSafe("container/stdout", stdoutPacket(string(buffer[:n])))
			}
			if err != nil {
				close(done)
				return
			}
		}
	}()
	inputCh := make(chan string, 16)
	connDone := make(chan struct{})
	go func() {
		defer close(connDone)
		for {
			var msg map[string]any
			if err := conn.ReadJSON(&msg); err != nil {
				return
			}
			event, _ := msg["event"].(string)
			data, _ := msg["data"].(map[string]any)
			if event != "container/input" {
				continue
			}
			input, _ := data["input"].(string)
			select {
			case inputCh <- input:
			case <-done:
				return
			}
		}
	}()
	for {
		select {
		case <-done:
			writeSafe("container/closed", map[string]any{"ok": true, "timestamp": now()})
			return
		case input := <-inputCh:
			_, _ = io.WriteString(dockerConn, input)
		case <-connDone:
			_ = dockerConn.Close()
			return
		}
	}
}

func openDockerAttach(containerID string) (net.Conn, *bufio.Reader, error) {
	socketPath := dockerSocketPath()
	conn, err := net.DialTimeout("unix", socketPath, 5*time.Second)
	if err != nil {
		return nil, nil, err
	}

	cleanup := true
	defer func() {
		if cleanup {
			_ = conn.Close()
		}
	}()

	path := "/containers/" + url.PathEscape(containerID) + "/attach?stream=1&stdin=1&stdout=1&stderr=1&logs=0"
	request := "POST " + path + " HTTP/1.1\r\n" +
		"Host: docker\r\n" +
		"User-Agent: beiming-daemon\r\n" +
		"Connection: Upgrade\r\n" +
		"Upgrade: tcp\r\n" +
		"Content-Length: 0\r\n\r\n"

	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := io.WriteString(conn, request); err != nil {
		return nil, nil, err
	}

	reader := bufio.NewReader(conn)
	statusLine, err := reader.ReadString('\n')
	if err != nil {
		return nil, nil, err
	}

	headers := strings.Builder{}
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return nil, nil, err
		}
		if line == "\r\n" || line == "\n" {
			break
		}
		headers.WriteString(line)
	}

	if !strings.Contains(statusLine, " 200 ") && !strings.Contains(statusLine, " 101 ") {
		body := make([]byte, 1024)
		_ = conn.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
		n, _ := reader.Read(body)
		message := strings.TrimSpace(statusLine + headers.String() + string(body[:n]))
		if message == "" {
			message = "docker attach failed"
		}
		return nil, nil, errors.New(message)
	}

	_ = conn.SetDeadline(time.Time{})
	cleanup = false
	return conn, reader, nil
}

func dockerSocketPath() string {
	host := strings.TrimSpace(os.Getenv("DOCKER_HOST"))
	if strings.HasPrefix(host, "unix://") {
		return strings.TrimPrefix(host, "unix://")
	}
	return "/var/run/docker.sock"
}

func (s *Store) Load() error {
	if _, err := os.Stat(s.Config); errors.Is(err, os.ErrNotExist) {
		return nil
	}
	bytes, err := os.ReadFile(s.Config)
	if err != nil {
		return err
	}
	var configs []InstanceConfig
	if err := json.Unmarshal(bytes, &configs); err != nil {
		return err
	}
	for _, cfg := range configs {
		cfg = normalizeConfig(cfg)
		s.Instances[cfg.ID] = newInstance(cfg)
	}
	return nil
}

func (s *Store) Save() error {
	if err := os.MkdirAll(filepath.Dir(s.Config), 0755); err != nil {
		return err
	}
	configs := []InstanceConfig{}
	for _, inst := range s.Instances {
		configs = append(configs, inst.Config)
	}
	bytes, err := json.MarshalIndent(configs, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(s.Config, bytes, 0644)
}

func (s *Store) List() []map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	result := []map[string]any{}
	for _, inst := range s.Instances {
		result = append(result, inst.Summary())
	}
	return result
}

func (s *Store) Upsert(cfg InstanceConfig) (map[string]any, error) {
	cfg = normalizeConfig(cfg)
	s.mu.Lock()
	defer s.mu.Unlock()
	if previous, ok := s.Instances[cfg.ID]; ok && previous.Cmd != nil {
		return nil, errors.New("stop the instance before changing its launch config")
	}
	inst := newInstance(cfg)
	if previous, ok := s.Instances[cfg.ID]; ok {
		inst.Output = previous.Output
		inst.Watchers = previous.Watchers
	}
	s.Instances[cfg.ID] = inst
	return inst.Summary(), s.Save()
}

func (s *Store) Delete(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	inst, ok := s.Instances[id]
	if !ok {
		return errors.New("unknown instance: " + id)
	}
	if inst.Cmd != nil {
		return errors.New("stop the instance before deleting it")
	}
	delete(s.Instances, id)
	return s.Save()
}

func (s *Store) Get(id string) (*Instance, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	inst, ok := s.Instances[id]
	if !ok {
		return nil, errors.New("unknown instance: " + id)
	}
	return inst, nil
}

func (s *Store) Start(id string) (*Instance, error) {
	inst, err := s.Get(id)
	if err != nil {
		return nil, err
	}
	inst.mu.Lock()
	defer inst.mu.Unlock()
	if inst.Cmd != nil {
		return inst, nil
	}
	inst.Output = ""
	inst.StartedAt = now()
	inst.StoppedAt = 0
	inst.ExitCode = nil
	inst.append("[Beiming] Starting "+inst.Config.Name+"\r\n", s.Limit)

	cmd := shellCommand(inst.Config.Command)
	cmd.Dir = inst.Config.Cwd
	cmd.Env = os.Environ()
	for key, value := range inst.Config.Env {
		cmd.Env = append(cmd.Env, key+"="+value)
	}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	inst.Cmd = cmd
	inst.Stdin = stdin
	go streamPipe(inst, stdout, s.Limit)
	go streamPipe(inst, stderr, s.Limit)
	go func() {
		err := cmd.Wait()
		inst.mu.Lock()
		exit := cmd.ProcessState.ExitCode()
		inst.Cmd = nil
		inst.Stdin = nil
		inst.ExitCode = &exit
		inst.StoppedAt = now()
		inst.mu.Unlock()
		message := fmt.Sprintf("\r\n[Beiming] Process exited with %d\r\n", exit)
		if err != nil {
			message = fmt.Sprintf("\r\n[Beiming] Process exited: %v\r\n", err)
		}
		inst.append(message, s.Limit)
	}()
	return inst, nil
}

func (s *Store) Stop(id string, kill bool) map[string]any {
	inst, err := s.Get(id)
	if err != nil {
		return map[string]any{"error": err.Error()}
	}
	inst.mu.Lock()
	defer inst.mu.Unlock()
	if inst.Cmd == nil || inst.Cmd.Process == nil {
		return inst.Summary()
	}
	if kill {
		_ = inst.Cmd.Process.Kill()
	} else {
		_ = inst.Cmd.Process.Signal(os.Interrupt)
	}
	return inst.Summary()
}

func (s *Store) SendCommand(id string, command string) error {
	return s.WriteInput(id, command+"\n")
}

func (s *Store) WriteInput(id string, input string) error {
	inst, err := s.Get(id)
	if err != nil {
		return err
	}
	inst.mu.Lock()
	stdin := inst.Stdin
	inst.mu.Unlock()
	if stdin == nil {
		return errors.New("instance process is not running or stdin is unavailable")
	}
	_, err = io.WriteString(stdin, input)
	return err
}

func (s *Store) Output(id string) (string, int, error) {
	inst, err := s.Get(id)
	if err != nil {
		return "", s.Limit, err
	}
	inst.mu.Lock()
	defer inst.mu.Unlock()
	return inst.Output, s.Limit, nil
}

func (s *Store) AddWatcher(id string, conn *websocket.Conn) {
	inst, err := s.Get(id)
	if err != nil {
		return
	}
	inst.mu.Lock()
	defer inst.mu.Unlock()
	inst.Watchers[conn] = true
}

func (s *Store) RemoveWatcher(id string, conn *websocket.Conn) {
	inst, err := s.Get(id)
	if err != nil {
		return
	}
	inst.mu.Lock()
	defer inst.mu.Unlock()
	delete(inst.Watchers, conn)
}

func (s *Store) AutoStart() {
	for _, inst := range s.Instances {
		if inst.Config.AutoStart {
			_, _ = s.Start(inst.Config.ID)
		}
	}
}

func (i *Instance) Summary() map[string]any {
	i.mu.Lock()
	defer i.mu.Unlock()
	status := "stopped"
	if i.Cmd != nil {
		status = "running"
	}
	return map[string]any{
		"id":           i.Config.ID,
		"name":         i.Config.Name,
		"cwd":          i.Config.Cwd,
		"command":      i.Config.Command,
		"autoStart":    i.Config.AutoStart,
		"status":       status,
		"startedAt":    i.StartedAt,
		"stoppedAt":    i.StoppedAt,
		"exitCode":     i.ExitCode,
		"outputLength": len(i.Output),
	}
}

func (i *Instance) append(text string, limit int) {
	i.mu.Lock()
	i.Output += text
	if len(i.Output) > limit {
		i.Output = i.Output[len(i.Output)-limit:]
	}
	watchers := make([]*websocket.Conn, 0, len(i.Watchers))
	for watcher := range i.Watchers {
		watchers = append(watchers, watcher)
	}
	i.mu.Unlock()
	for _, watcher := range watchers {
		writeWSEvent(watcher, "instance/stdout", stdoutPacket(text))
	}
}

func newInstance(cfg InstanceConfig) *Instance {
	return &Instance{Config: cfg, Watchers: map[*websocket.Conn]bool{}}
}

func normalizeConfig(cfg InstanceConfig) InstanceConfig {
	cfg.ID = strings.TrimSpace(cfg.ID)
	cfg.Name = strings.TrimSpace(cfg.Name)
	cfg.Command = strings.TrimSpace(cfg.Command)
	if cfg.ID == "" {
		cfg.ID = cfg.Name
	}
	if cfg.Name == "" {
		cfg.Name = cfg.ID
	}
	if cfg.Cwd == "" {
		cfg.Cwd, _ = os.Getwd()
	}
	if cfg.Env == nil {
		cfg.Env = map[string]string{}
	}
	return cfg
}

func streamPipe(inst *Instance, reader io.Reader, limit int) {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	for scanner.Scan() {
		inst.append(scanner.Text()+"\r\n", limit)
	}
}

func runShell(command string) (string, error) {
	cmd := shellCommand(command)
	output, err := cmd.CombinedOutput()
	return string(output), err
}

func runJSONLines(command string) ([]map[string]any, error) {
	text, err := runShell(command)
	if err != nil {
		return nil, err
	}
	result := []map[string]any{}
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		var item map[string]any
		if err := json.Unmarshal([]byte(line), &item); err == nil {
			result = append(result, item)
		}
	}
	return result, nil
}

func numberFromAny(value any) float64 {
	switch typed := value.(type) {
	case float64:
		return typed
	case float32:
		return float64(typed)
	case int:
		return float64(typed)
	case int64:
		return float64(typed)
	case json.Number:
		number, _ := typed.Float64()
		return number
	default:
		return 0
	}
}

func shellCommand(command string) *exec.Cmd {
	if runtime.GOOS == "windows" {
		return exec.Command("cmd", "/C", command)
	}
	return exec.Command("sh", "-lc", command)
}

func shellCommandContext(ctx context.Context, command string) *exec.Cmd {
	if runtime.GOOS == "windows" {
		return exec.CommandContext(ctx, "cmd", "/C", command)
	}
	cmd := exec.CommandContext(ctx, "sh", "-lc", command)
	setCommandGroup(cmd)
	return cmd
}

func collectMetrics() map[string]any {
	if runtime.GOOS == "linux" {
		return collectLinuxMetrics()
	}
	if runtime.GOOS == "windows" {
		return collectWindowsMetrics()
	}
	return map[string]any{
		"cpuLine":    "",
		"cpuCores":   fmt.Sprintf("%d", runtime.NumCPU()),
		"cpuThreads": fmt.Sprintf("%d", runtime.NumCPU()),
		"mem":        "0 0",
		"swap":       "0 0",
		"load":       "0 0 0",
		"disk":       "0 0 0%",
		"net":        "0 0",
	}
}

func collectWindowsMetrics() map[string]any {
	memTotal, memFree := windowsMemoryMb()
	diskTotal, diskFree := windowsDiskMb()
	diskUsed := diskTotal - diskFree
	diskPercent := 0
	if diskTotal > 0 {
		diskPercent = int(float64(diskUsed) / float64(diskTotal) * 100)
	}
	return map[string]any{
		"cpuLine":    "",
		"cpuCores":   fmt.Sprintf("%d", runtime.NumCPU()),
		"cpuThreads": fmt.Sprintf("%d", runtime.NumCPU()),
		"mem":        fmt.Sprintf("%d %d", memTotal, memTotal-memFree),
		"swap":       "0 0",
		"load":       "0 0 0",
		"disk":       fmt.Sprintf("%d %d %d%%", diskTotal, diskUsed, diskPercent),
		"net":        "0 0",
	}
}

func windowsMemoryMb() (int64, int64) {
	text, err := runShell("powershell -NoProfile -Command \"$m=Get-CimInstance Win32_OperatingSystem; [math]::Round($m.TotalVisibleMemorySize/1024); [math]::Round($m.FreePhysicalMemory/1024)\"")
	if err != nil {
		return 0, 0
	}
	fields := strings.Fields(text)
	if len(fields) < 2 {
		return 0, 0
	}
	var total, free int64
	fmt.Sscanf(fields[0], "%d", &total)
	fmt.Sscanf(fields[1], "%d", &free)
	return total, free
}

func windowsDiskMb() (int64, int64) {
	text, err := runShell("powershell -NoProfile -Command \"$d=Get-CimInstance Win32_LogicalDisk -Filter \\\"DeviceID='C:'\\\"; [math]::Round($d.Size/1MB); [math]::Round($d.FreeSpace/1MB)\"")
	if err != nil {
		return 0, 0
	}
	fields := strings.Fields(text)
	if len(fields) < 2 {
		return 0, 0
	}
	var total, free int64
	fmt.Sscanf(fields[0], "%d", &total)
	fmt.Sscanf(fields[1], "%d", &free)
	return total, free
}

func collectLinuxMetrics() map[string]any {
	mem := readMemInfo()
	totalMb := mem["MemTotal"] / 1024
	availableMb := mem["MemAvailable"] / 1024
	usedMb := totalMb - availableMb
	swapTotalMb := mem["SwapTotal"] / 1024
	swapFreeMb := mem["SwapFree"] / 1024
	swapUsedMb := swapTotalMb - swapFreeMb
	rx, tx := readNetDev()
	load := readFirstFields("/proc/loadavg", 3)
	if load == "" {
		load = "0 0 0"
	}
	disk := "0 0 0%"
	if text, err := runShell("df -Pm / | awk 'NR==2 {print $2,$3,$5}'"); err == nil && strings.TrimSpace(text) != "" {
		disk = strings.TrimSpace(text)
	}
	return map[string]any{
		"cpuLine":    cpuLineFromProc(),
		"cpuCores":   fmt.Sprintf("%d", linuxPhysicalCores()),
		"cpuThreads": fmt.Sprintf("%d", runtime.NumCPU()),
		"mem":        fmt.Sprintf("%d %d", totalMb, usedMb),
		"swap":       fmt.Sprintf("%d %d", swapTotalMb, swapUsedMb),
		"load":       load,
		"disk":       disk,
		"net":        fmt.Sprintf("%d %d", rx, tx),
	}
}

func linuxPhysicalCores() int {
	if text, err := runShell("lscpu 2>/dev/null | awk -F: '/^Core\\(s\\) per socket:/ {gsub(/ /,\"\",$2); c=$2} /^Socket\\(s\\):/ {gsub(/ /,\"\",$2); s=$2} END {if (c && s) print c*s}'"); err == nil {
		var cores int
		if _, scanErr := fmt.Sscanf(strings.TrimSpace(text), "%d", &cores); scanErr == nil && cores > 0 {
			return cores
		}
	}
	seen := map[string]bool{}
	bytes, err := os.ReadFile("/proc/cpuinfo")
	if err == nil {
		physicalID := ""
		coreID := ""
		for _, line := range strings.Split(string(bytes), "\n") {
			if strings.HasPrefix(line, "physical id") {
				physicalID = strings.TrimSpace(strings.SplitN(line, ":", 2)[1])
			}
			if strings.HasPrefix(line, "core id") {
				coreID = strings.TrimSpace(strings.SplitN(line, ":", 2)[1])
			}
			if strings.TrimSpace(line) == "" && physicalID != "" && coreID != "" {
				seen[physicalID+":"+coreID] = true
				physicalID = ""
				coreID = ""
			}
		}
		if len(seen) > 0 {
			return len(seen)
		}
	}
	return runtime.NumCPU()
}

func readMemInfo() map[string]int64 {
	result := map[string]int64{}
	bytes, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return result
	}
	for _, line := range strings.Split(string(bytes), "\n") {
		fields := strings.Fields(strings.ReplaceAll(line, ":", ""))
		if len(fields) >= 2 {
			var value int64
			fmt.Sscanf(fields[1], "%d", &value)
			result[fields[0]] = value
		}
	}
	return result
}

func readNetDev() (int64, int64) {
	bytes, err := os.ReadFile("/proc/net/dev")
	if err != nil {
		return 0, 0
	}
	var rx, tx int64
	for _, line := range strings.Split(string(bytes), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || !strings.Contains(line, ":") || strings.HasPrefix(line, "lo:") {
			continue
		}
		parts := strings.Fields(strings.ReplaceAll(line, ":", " "))
		if len(parts) >= 17 {
			var nextRx, nextTx int64
			fmt.Sscanf(parts[1], "%d", &nextRx)
			fmt.Sscanf(parts[9], "%d", &nextTx)
			rx += nextRx
			tx += nextTx
		}
	}
	return rx, tx
}

func readFirstFields(file string, count int) string {
	bytes, err := os.ReadFile(file)
	if err != nil {
		return ""
	}
	fields := strings.Fields(string(bytes))
	if len(fields) < count {
		count = len(fields)
	}
	return strings.Join(fields[:count], " ")
}

func cpuLineFromProc() string {
	first, err := readCPUStat()
	if err != nil {
		return ""
	}
	time.Sleep(180 * time.Millisecond)
	second, err := readCPUStat()
	if err != nil {
		return ""
	}
	totalDelta := second.total - first.total
	idleDelta := second.idle - first.idle
	idle := 0.0
	if totalDelta > 0 {
		idle = float64(idleDelta) / float64(totalDelta) * 100
	}
	return fmt.Sprintf("%%Cpu(s): %.2f id", idle)
}

type cpuStat struct {
	total uint64
	idle  uint64
}

func readCPUStat() (cpuStat, error) {
	bytes, err := os.ReadFile("/proc/stat")
	if err != nil {
		return cpuStat{}, err
	}
	line := strings.SplitN(string(bytes), "\n", 2)[0]
	fields := strings.Fields(line)
	var stat cpuStat
	for index, field := range fields[1:] {
		var value uint64
		fmt.Sscanf(field, "%d", &value)
		stat.total += value
		if index == 3 || index == 4 {
			stat.idle += value
		}
	}
	return stat, nil
}

func shellQuote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "'\\''") + "'"
}

func stringValue(row map[string]any, key string) string {
	if row == nil {
		return ""
	}
	if value, ok := row[key].(string); ok {
		return value
	}
	return fmt.Sprintf("%v", row[key])
}

func safeUploadID(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return ""
	}
	replacer := strings.NewReplacer("/", "_", "\\", "_", "..", "_", ":", "_")
	value = replacer.Replace(value)
	if len(value) > 160 {
		return value[:160]
	}
	return value
}

func safeDockerName(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	var builder strings.Builder
	for _, char := range value {
		if (char >= 'a' && char <= 'z') || (char >= '0' && char <= '9') || char == '-' || char == '_' || char == '.' {
			builder.WriteRune(char)
		} else {
			builder.WriteByte('-')
		}
		if builder.Len() >= 80 {
			break
		}
	}
	return strings.Trim(builder.String(), "-_.")
}

func parseRangeHeader(header string, size int64) (int64, int64, bool, bool) {
	if size == 0 {
		return 0, -1, false, true
	}
	if strings.TrimSpace(header) == "" {
		return 0, size - 1, false, true
	}
	if !strings.HasPrefix(header, "bytes=") {
		return 0, 0, false, false
	}
	value := strings.TrimPrefix(header, "bytes=")
	parts := strings.SplitN(value, "-", 2)
	if len(parts) != 2 {
		return 0, 0, false, false
	}
	var start, end int64
	if parts[0] == "" {
		var suffix int64
		if _, err := fmt.Sscanf(parts[1], "%d", &suffix); err != nil || suffix <= 0 {
			return 0, 0, false, false
		}
		if suffix > size {
			suffix = size
		}
		start = size - suffix
		end = size - 1
	} else {
		if _, err := fmt.Sscanf(parts[0], "%d", &start); err != nil {
			return 0, 0, false, false
		}
		if parts[1] == "" {
			end = size - 1
		} else if _, err := fmt.Sscanf(parts[1], "%d", &end); err != nil {
			return 0, 0, false, false
		}
	}
	if start < 0 || start >= size || end < start {
		return 0, 0, false, false
	}
	if end >= size {
		end = size - 1
	}
	return start, end, true, true
}

func escapeHeaderFilename(value string) string {
	value = strings.ReplaceAll(value, "\\", "_")
	value = strings.ReplaceAll(value, "\"", "_")
	value = strings.ReplaceAll(value, "\r", "_")
	value = strings.ReplaceAll(value, "\n", "_")
	return value
}

func setDownloadNameHeaders(w http.ResponseWriter, name string) {
	escaped := asciiHeaderFilename(name)
	encoded := url.QueryEscape(name)
	encoded = strings.ReplaceAll(encoded, "+", "%20")
	w.Header().Set("Content-Disposition", "attachment; filename=\""+escaped+"\"; filename*=UTF-8''"+encoded)
	w.Header().Set("X-File-Name", name)
}

func asciiHeaderFilename(value string) string {
	value = escapeHeaderFilename(value)
	var builder strings.Builder
	for _, char := range value {
		if char >= 32 && char <= 126 {
			builder.WriteRune(char)
		} else {
			builder.WriteByte('_')
		}
	}
	if builder.Len() == 0 {
		return "download"
	}
	return builder.String()
}

func writeOK(w http.ResponseWriter, data any) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "data": data})
}

func writeError(w http.ResponseWriter, err error) {
	writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "message": err.Error()})
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	writeCORSHeaders(w)
	w.Header().Set("content-type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func writeCORSHeaders(w http.ResponseWriter) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, HEAD, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, Range")
	w.Header().Set("Access-Control-Expose-Headers", "Accept-Ranges, Content-Disposition, Content-Length, Content-Range, Content-Type, X-File-Name, X-File-Size")
}

func writeWSEvent(conn *websocket.Conn, event string, payload any) {
	_ = conn.WriteJSON(map[string]any{"event": event, "payload": payload})
}

func stdoutPacket(text string) map[string]any {
	return map[string]any{"ok": true, "data": map[string]any{"text": text}, "timestamp": now()}
}

func now() int64 {
	return time.Now().UnixMilli()
}

func envDefault(key string, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func envInt(key string, fallback int) int {
	var value int
	if _, err := fmt.Sscanf(os.Getenv(key), "%d", &value); err == nil {
		return value
	}
	return fallback
}
