package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	listenAddr       = "127.0.0.1:18787"
	defaultThreads   = 32
	maxThreads       = 256
	activeWindow     = 1500 * time.Millisecond
	speedWindow      = 2500 * time.Millisecond
	maxRetryPerRange = 4
	minStealSize     = 512 * 1024
)

type downloadRequest struct {
	URLs           []string `json:"urls"`
	URL            string   `json:"url"`
	Name           string   `json:"name"`
	Size           int64    `json:"size"`
	Threads        int      `json:"threads"`
	PromptSavePath bool     `json:"promptSavePath"`
}

type taskStatus struct {
	ID                string `json:"id"`
	Name              string `json:"name"`
	Path              string `json:"path"`
	State             string `json:"state"`
	Size              int64  `json:"size"`
	Downloaded        int64  `json:"downloaded"`
	Percent           int    `json:"percent"`
	Speed             int64  `json:"speed"`
	Threads           int    `json:"threads"`
	ActiveThreads     int    `json:"activeThreads"`
	AverageThreadRate int64  `json:"averageThreadRate"`
	Error             string `json:"error"`
}

type rangePart struct {
	Start      int64
	End        int64
	Downloaded int64
	Done       bool
}

type speedSample struct {
	At    time.Time
	Bytes int64
}

type task struct {
	mu           sync.Mutex
	id           string
	name         string
	path         string
	urls         []string
	size         int64
	threads      int
	state        string
	errText      string
	parts        []*rangePart
	downloaded   int64
	activeSeenAt map[int]time.Time
	samples      []speedSample
	ctx          context.Context
	cancel       context.CancelFunc
	done         chan struct{}
}

type server struct {
	mu    sync.Mutex
	tasks map[string]*task
}

var localDownloaderServer *server
var errSavePathCancelled = errors.New("已取消选择保存位置")

func main() {
	s := &server{tasks: map[string]*task{}}
	localDownloaderServer = s
	setupDesktopIntegration()
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.withCORS(s.handleHealth))
	mux.HandleFunc("/downloads", s.withCORS(s.handleDownloads))
	mux.HandleFunc("/downloads/", s.withCORS(s.handleDownloadAction))
	log.Printf("beiming local downloader listening on http://%s", listenAddr)
	if err := http.ListenAndServe(listenAddr, mux); err != nil {
		log.Fatal(err)
	}
}

func (s *server) withCORS(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin == "" || strings.HasPrefix(origin, "http://127.0.0.1:") || strings.HasPrefix(origin, "http://localhost:") {
			if origin != "" {
				w.Header().Set("Access-Control-Allow-Origin", origin)
			} else {
				w.Header().Set("Access-Control-Allow-Origin", "*")
			}
			w.Header().Set("Access-Control-Allow-Headers", "content-type")
			w.Header().Set("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next(w, r)
	}
}

func (s *server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]any{"ok": true, "name": "beiming-local-downloader"})
}

func (s *server) handleDownloads(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req downloadRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	urls := normalizeURLs(req)
	if len(urls) == 0 {
		http.Error(w, "missing download url", http.StatusBadRequest)
		return
	}
	if req.Size <= 0 {
		http.Error(w, "missing file size", http.StatusBadRequest)
		return
	}
	threads := req.Threads
	if threads <= 0 {
		threads = defaultThreads
	}
	threads = max(1, min(maxThreads, min(threads, int(math.Ceil(float64(req.Size)/float64(512*1024))))))
	name := safeFileName(req.Name)
	var path string
	var err error
	if req.PromptSavePath {
		path, err = chooseDownloadPath(name)
	} else {
		path, err = uniqueDownloadPath(name)
	}
	if err != nil {
		if errors.Is(err, errSavePathCancelled) {
			writeJSONError(w, err.Error(), http.StatusBadRequest)
			return
		}
		writeJSONError(w, err.Error(), http.StatusInternalServerError)
		return
	}
	t := newTask(urls, name, path, req.Size, threads)
	s.mu.Lock()
	s.tasks[t.id] = t
	s.mu.Unlock()
	t.start()
	writeJSON(w, t.snapshot())
}

func (s *server) handleDownloadAction(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/downloads/"), "/")
	if len(parts) == 0 || parts[0] == "" {
		http.Error(w, "missing task id", http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	t := s.tasks[parts[0]]
	s.mu.Unlock()
	if t == nil {
		http.Error(w, "task not found", http.StatusNotFound)
		return
	}
	if len(parts) == 1 && r.Method == http.MethodGet {
		writeJSON(w, t.snapshot())
		return
	}
	if len(parts) != 2 || r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	switch parts[1] {
	case "cancel":
		t.cancelTask()
	case "pause":
		t.pause()
	case "resume":
		t.resume()
	case "reveal":
		if err := revealDownloadedFile(t.path); err != nil {
			writeJSONError(w, err.Error(), http.StatusInternalServerError)
			return
		}
	default:
		http.Error(w, "unknown action", http.StatusNotFound)
		return
	}
	writeJSON(w, t.snapshot())
}

func newTask(urls []string, name, path string, size int64, threads int) *task {
	segmentSize := int64(math.Ceil(float64(size) / float64(threads)))
	parts := make([]*rangePart, 0, threads)
	for index := 0; index < threads; index++ {
		start := int64(index) * segmentSize
		if start >= size {
			break
		}
		end := min(size-1, start+segmentSize-1)
		parts = append(parts, &rangePart{Start: start, End: end})
	}
	return &task{
		id:           strconv.FormatInt(time.Now().UnixNano(), 36),
		name:         name,
		path:         path,
		urls:         urls,
		size:         size,
		threads:      len(parts),
		state:        "queued",
		parts:        parts,
		activeSeenAt: map[int]time.Time{},
		samples:      []speedSample{{At: time.Now(), Bytes: 0}},
		done:         make(chan struct{}),
	}
}

func (t *task) start() {
	t.mu.Lock()
	if t.state == "downloading" {
		t.mu.Unlock()
		return
	}
	t.ctx, t.cancel = context.WithCancel(context.Background())
	t.state = "downloading"
	t.errText = ""
	t.mu.Unlock()

	go func() {
		err := t.run()
		t.mu.Lock()
		defer t.mu.Unlock()
		if err == nil {
			t.state = "done"
		} else if errors.Is(err, context.Canceled) {
			if t.state != "paused" && t.state != "cancelled" {
				t.state = "cancelled"
			}
		} else {
			t.state = "error"
			t.errText = err.Error()
		}
		select {
		case <-t.done:
		default:
			close(t.done)
		}
	}()
}

func (t *task) run() error {
	file, err := os.OpenFile(t.path, os.O_CREATE|os.O_RDWR, 0644)
	if err != nil {
		return err
	}
	defer file.Close()
	if err := file.Truncate(t.size); err != nil {
		return err
	}
	var wg sync.WaitGroup
	errCh := make(chan error, t.threads)
	for index := range t.parts {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			if err := t.downloadPart(workerID, file); err != nil {
				errCh <- err
			}
		}(index)
	}
	wg.Wait()
	close(errCh)
	for err := range errCh {
		if err != nil {
			return err
		}
	}
	return nil
}

func (t *task) downloadPart(workerID int, file *os.File) error {
	part := t.parts[workerID]
	retries := 0
	for {
		t.mu.Lock()
		start := part.Start + part.Downloaded
		end := part.End
		done := part.Done || start > end
		t.mu.Unlock()
		if done {
			if !t.stealWork(workerID) {
				return nil
			}
			retries = 0
			continue
		}
		if err := t.downloadRange(workerID, file, start, end); err != nil {
			if errors.Is(err, context.Canceled) {
				return err
			}
			retries++
			if retries >= maxRetryPerRange {
				return err
			}
			select {
			case <-t.ctx.Done():
				return t.ctx.Err()
			case <-time.After(time.Duration(retries) * 350 * time.Millisecond):
			}
			continue
		}
		retries = 0
	}
}

func (t *task) stealWork(workerID int) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	var victim *rangePart
	var maxRemain int64
	for index, part := range t.parts {
		if index == workerID || part.Done {
			continue
		}
		remain := part.End - part.Start + 1 - part.Downloaded
		if remain > maxRemain && remain >= minStealSize*2 {
			victim = part
			maxRemain = remain
		}
	}
	if victim == nil {
		return false
	}
	splitStart := victim.End - maxRemain/2 + 1
	part := t.parts[workerID]
	part.Start = splitStart
	part.End = victim.End
	part.Downloaded = 0
	part.Done = false
	victim.End = splitStart - 1
	return true
}

func (t *task) downloadRange(workerID int, file *os.File, start, end int64) error {
	client := newIndependentHTTPClient()
	req, err := http.NewRequestWithContext(t.ctx, http.MethodGet, t.urls[workerID%len(t.urls)], nil)
	if err != nil {
		return err
	}
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start, end))
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36")
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Connection", "close")
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusPartialContent {
		return fmt.Errorf("range request failed: %s", resp.Status)
	}
	buf := make([]byte, 256*1024)
	offset := start
	for {
		n, readErr := resp.Body.Read(buf)
		if n > 0 {
			allowed, trimmed := t.acceptRangeBytes(workerID, offset, int64(n))
			if allowed <= 0 {
				return nil
			}
			if _, err := file.WriteAt(buf[:allowed], offset); err != nil {
				return err
			}
			offset += allowed
			if trimmed {
				return nil
			}
		}
		if readErr == io.EOF {
			t.mu.Lock()
			part := t.parts[workerID]
			if part.Start+part.Downloaded > part.End {
				t.parts[workerID].Done = true
			}
			t.mu.Unlock()
			return nil
		}
		if readErr != nil {
			return readErr
		}
		select {
		case <-t.ctx.Done():
			return t.ctx.Err()
		default:
		}
	}
}

func (t *task) acceptRangeBytes(workerID int, offset int64, bytesRead int64) (int64, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	part := t.parts[workerID]
	if part.Done || offset > part.End {
		return 0, true
	}
	allowed := min(bytesRead, part.End-offset+1)
	part.Downloaded += allowed
	if part.Start+part.Downloaded > part.End {
		part.Done = true
	}
	t.downloaded += allowed
	now := time.Now()
	t.activeSeenAt[workerID] = now
	t.samples = append(t.samples, speedSample{At: now, Bytes: t.downloaded})
	cutoff := now.Add(-speedWindow)
	first := 0
	for first < len(t.samples)-1 && t.samples[first].At.Before(cutoff) {
		first++
	}
	if first > 0 {
		t.samples = append([]speedSample(nil), t.samples[first:]...)
	}
	return allowed, allowed < bytesRead
}

func (t *task) addProgress(workerID int, delta int64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	part := t.parts[workerID]
	part.Downloaded += delta
	if part.Start+part.Downloaded > part.End {
		part.Done = true
	}
	t.downloaded += delta
	now := time.Now()
	t.activeSeenAt[workerID] = now
	t.samples = append(t.samples, speedSample{At: now, Bytes: t.downloaded})
	cutoff := now.Add(-speedWindow)
	first := 0
	for first < len(t.samples)-1 && t.samples[first].At.Before(cutoff) {
		first++
	}
	if first > 0 {
		t.samples = append([]speedSample(nil), t.samples[first:]...)
	}
}

func (t *task) snapshot() taskStatus {
	t.mu.Lock()
	defer t.mu.Unlock()
	now := time.Now()
	finalState := t.state == "done" || t.state == "cancelled" || t.state == "error"
	if finalState {
		t.activeSeenAt = map[int]time.Time{}
	} else {
		for workerID, seenAt := range t.activeSeenAt {
			if now.Sub(seenAt) > activeWindow {
				delete(t.activeSeenAt, workerID)
			}
		}
	}
	speed := int64(0)
	if !finalState && len(t.samples) >= 2 {
		first := t.samples[0]
		last := t.samples[len(t.samples)-1]
		seconds := last.At.Sub(first.At).Seconds()
		if seconds > 0 {
			speed = int64(float64(last.Bytes-first.Bytes) / seconds)
		}
	}
	active := len(t.activeSeenAt)
	percent := 0
	if t.size > 0 {
		percent = int(math.Min(100, math.Round(float64(t.downloaded)*100/float64(t.size))))
	}
	if t.state == "done" {
		percent = 100
	}
	avg := int64(0)
	if active > 0 {
		avg = speed / int64(active)
	}
	return taskStatus{
		ID:                t.id,
		Name:              t.name,
		Path:              t.path,
		State:             t.state,
		Size:              t.size,
		Downloaded:        t.downloaded,
		Percent:           percent,
		Speed:             speed,
		Threads:           t.threads,
		ActiveThreads:     active,
		AverageThreadRate: avg,
		Error:             t.errText,
	}
}

func (t *task) pause() {
	t.mu.Lock()
	if t.state != "downloading" {
		t.mu.Unlock()
		return
	}
	t.state = "paused"
	cancel := t.cancel
	t.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (t *task) resume() {
	t.mu.Lock()
	if t.state != "paused" && t.state != "error" {
		t.mu.Unlock()
		return
	}
	t.done = make(chan struct{})
	t.mu.Unlock()
	t.start()
}

func (t *task) cancelTask() {
	t.mu.Lock()
	t.state = "cancelled"
	cancel := t.cancel
	t.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func newIndependentHTTPClient() *http.Client {
	dialer := &net.Dialer{Timeout: 15 * time.Second, KeepAlive: -1}
	tr := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           dialer.DialContext,
		ForceAttemptHTTP2:     false,
		DisableKeepAlives:     true,
		MaxIdleConns:          0,
		MaxIdleConnsPerHost:   -1,
		TLSHandshakeTimeout:   15 * time.Second,
		ResponseHeaderTimeout: 25 * time.Second,
		ExpectContinueTimeout: time.Second,
		TLSClientConfig:       &tls.Config{MinVersion: tls.VersionTLS12},
	}
	client := &http.Client{Transport: tr}
	client.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		if len(via) > 0 {
			req.Header.Set("Range", via[0].Header.Get("Range"))
			req.Header.Set("User-Agent", via[0].Header.Get("User-Agent"))
			req.Header.Set("Accept", "*/*")
			req.Header.Set("Connection", "close")
		}
		if len(via) >= 8 {
			return http.ErrUseLastResponse
		}
		return nil
	}
	return client
}

func normalizeURLs(req downloadRequest) []string {
	values := append([]string{}, req.URLs...)
	if req.URL != "" {
		values = append(values, req.URL)
	}
	seen := map[string]bool{}
	out := []string{}
	for _, item := range values {
		item = strings.TrimSpace(item)
		if item == "" || seen[item] {
			continue
		}
		parsed, err := url.Parse(item)
		if err != nil || parsed.Scheme == "" || parsed.Host == "" {
			continue
		}
		seen[item] = true
		out = append(out, item)
	}
	return out
}

func safeFileName(name string) string {
	name = strings.TrimSpace(name)
	if name == "" {
		name = "download.bin"
	}
	name = filepath.Base(name)
	replacer := strings.NewReplacer("<", "_", ">", "_", ":", "_", "\"", "_", "/", "_", "\\", "_", "|", "_", "?", "_", "*", "_")
	name = replacer.Replace(name)
	if strings.Trim(name, ". ") == "" {
		return "download.bin"
	}
	return name
}

func uniqueDownloadPath(name string) (string, error) {
	dir, err := downloadsDir()
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return "", err
	}
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	for index := 0; index < 1000; index++ {
		candidate := name
		if index > 0 {
			candidate = fmt.Sprintf("%s (%d)%s", base, index, ext)
		}
		path := filepath.Join(dir, candidate)
		if _, err := os.Stat(path); os.IsNotExist(err) {
			return path, nil
		}
	}
	return "", fmt.Errorf("cannot find unique filename for %s", name)
}

func downloadsDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	if runtime.GOOS == "windows" {
		if userProfile := os.Getenv("USERPROFILE"); userProfile != "" {
			return filepath.Join(userProfile, "Downloads"), nil
		}
	}
	return filepath.Join(home, "Downloads"), nil
}

func writeJSON(w http.ResponseWriter, payload any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(payload)
}

func writeJSONError(w http.ResponseWriter, message string, status int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": false, "message": message})
}

type traySummary struct {
	Title        string
	State        string
	Task         string
	Path         string
	Speed        string
	UploadSpeed  string
	Threads      string
	Percent      string
	PercentValue int
}

func currentTraySummary() traySummary {
	summary := traySummary{
		Title:       "北冥本地下载器",
		State:       "已连接",
		Task:        "暂无任务",
		Speed:       "0 B/s",
		UploadSpeed: "0 B/s",
		Threads:     "0/0 线程",
		Percent:     "-",
	}
	if localDownloaderServer == nil {
		return summary
	}
	localDownloaderServer.mu.Lock()
	tasks := make([]*task, 0, len(localDownloaderServer.tasks))
	for _, item := range localDownloaderServer.tasks {
		tasks = append(tasks, item)
	}
	localDownloaderServer.mu.Unlock()
	if len(tasks) == 0 {
		return summary
	}
	var active *taskStatus
	for _, item := range tasks {
		status := item.snapshot()
		if status.State == "downloading" || status.State == "queued" || status.State == "paused" {
			active = &status
			break
		}
	}
	if active == nil {
		var latest *taskStatus
		for _, item := range tasks {
			status := item.snapshot()
			if latest == nil || status.ID > latest.ID {
				latest = &status
			}
		}
		if latest == nil {
			return summary
		}
		stateText := map[string]string{
			"done":      "已完成",
			"cancelled": "已取消",
			"error":     "下载失败",
		}[latest.State]
		if stateText != "" {
			summary.State = stateText
		}
		if latest.State == "error" && latest.Error != "" {
			summary.Task = latest.Error
		}
		if latest.State == "done" {
			summary.Percent = "100%"
			summary.PercentValue = 100
		}
		return summary
	}
	stateText := map[string]string{
		"downloading": "下载中",
		"queued":      "排队中",
		"paused":      "已暂停",
		"done":        "已完成",
		"cancelled":   "已取消",
		"error":       "下载失败",
	}[active.State]
	if stateText == "" {
		stateText = active.State
	}
	summary.State = stateText
	summary.Task = active.Name
	summary.Path = active.Path
	if active.Error != "" {
		summary.Task = active.Error
	}
	summary.Speed = formatTrayRate(active.Speed)
	summary.Threads = fmt.Sprintf("%d/%d 线程", active.ActiveThreads, active.Threads)
	summary.Percent = fmt.Sprintf("%d%%", active.Percent)
	summary.PercentValue = active.Percent
	return summary
}

func formatTrayRate(bytesPerSecond int64) string {
	if bytesPerSecond <= 0 {
		return "0 B/s"
	}
	units := []string{"B/s", "KB/s", "MB/s", "GB/s"}
	value := float64(bytesPerSecond)
	unit := 0
	for value >= 1024 && unit < len(units)-1 {
		value /= 1024
		unit++
	}
	if unit == 0 {
		return fmt.Sprintf("%d %s", int64(value), units[unit])
	}
	return fmt.Sprintf("%.1f %s", value, units[unit])
}
