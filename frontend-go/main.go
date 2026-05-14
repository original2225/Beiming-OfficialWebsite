package main

import (
	"embed"
	"errors"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path"
	"strings"
)

//go:embed dist/*
var embeddedDist embed.FS

func main() {
	host := flag.String("host", envDefault("FRONTEND_HOST", "127.0.0.1"), "listen host")
	port := flag.String("port", envDefault("FRONTEND_PORT", "5173"), "listen port")
	flag.Parse()

	dist, err := fs.Sub(embeddedDist, "dist")
	if err != nil {
		log.Fatalf("load embedded dist: %v", err)
	}

	mux := http.NewServeMux()
	mux.Handle("/", spaHandler(http.FS(dist)))

	addr := fmt.Sprintf("%s:%s", *host, *port)
	log.Printf("Beiming frontend listening on http://%s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}

func spaHandler(fileSystem http.FileSystem) http.Handler {
	fileServer := http.FileServer(fileSystem)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestPath := path.Clean("/" + strings.TrimPrefix(r.URL.Path, "/"))
		if requestPath == "/" {
			fileServer.ServeHTTP(w, r)
			return
		}

		file, err := fileSystem.Open(strings.TrimPrefix(requestPath, "/"))
		if err == nil {
			_ = file.Close()
			fileServer.ServeHTTP(w, r)
			return
		}
		if !errors.Is(err, fs.ErrNotExist) && !os.IsNotExist(err) {
			http.Error(w, http.StatusText(http.StatusInternalServerError), http.StatusInternalServerError)
			return
		}

		r.URL.Path = "/"
		fileServer.ServeHTTP(w, r)
	})
}

func envDefault(key string, fallback string) string {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	return value
}
