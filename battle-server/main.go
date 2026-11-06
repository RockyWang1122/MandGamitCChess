package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

// 橘中弈象棋好友对战服务器：纯 WebSocket 转发。
//
// 默认监听 :9001，WebSocket 路径 /battle：
//
//	./battle-server --addr=:9001 --path=/battle
//
// 也可直接启用 TLS（生产建议前置 Nginx 做 WSS + 证书，本服务仅监听内网）：
//
//	./battle-server --cert=/path/fullchain.pem --key=/path/privkey.pem
func main() {
	addr := flag.String("addr", ":9001", "HTTP 监听地址")
	path := flag.String("path", "/battle", "WebSocket 升级路径")
	certFile := flag.String("cert", "", "TLS 证书路径（为空则仅监听明文 ws，建议由 Nginx 终结 TLS）")
	keyFile := flag.String("key", "", "TLS 私钥路径")
	roomTTL := flag.Duration("room-ttl", 10*time.Minute, "房间无人在线后的保留时长")
	allowedOrigins := flag.String("origins", "",
		"允许的 Origin host 白名单，逗号分隔；留空表示允许全部（APP 不发 Origin）")
	flag.Parse()

	hub := newHub(*roomTTL)

	originSet := make(map[string]bool)
	for _, o := range strings.Split(*allowedOrigins, ",") {
		o = strings.TrimSpace(o)
		if o != "" {
			originSet[o] = true
		}
	}
	checkOrigin := func(r *http.Request) bool {
		origin := r.Header.Get("Origin")
		if origin == "" {
			return true // Android 原生客户端不带 Origin
		}
		if len(originSet) == 0 {
			return true
		}
		u, err := url.Parse(origin)
		if err != nil {
			return false
		}
		return originSet[u.Host]
	}

	mux := http.NewServeMux()
	mux.HandleFunc(*path, serveWS(hub, checkOrigin))
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		_, _ = w.Write([]byte("ok\n"))
	})

	srv := &http.Server{
		Addr:              *addr,
		Handler:           withAccessLog(mux),
		ReadHeaderTimeout: 10 * time.Second,
	}

	serverErr := make(chan error, 1)
	go func() {
		log.Printf("battle server listening on %s (path=%s, tls=%v, room-ttl=%s)",
			*addr, *path, *certFile != "", roomTTL)
		if *certFile != "" && *keyFile != "" {
			serverErr <- srv.ListenAndServeTLS(*certFile, *keyFile)
		} else {
			serverErr <- srv.ListenAndServe()
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)

	select {
	case err := <-serverErr:
		if err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	case sig := <-stop:
		log.Printf("received signal %s, shutting down…", sig)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := srv.Shutdown(ctx); err != nil {
			log.Printf("graceful shutdown failed: %v", err)
		}
	}
}

// withAccessLog 仅记录非 WebSocket 的普通 HTTP 访问（Upgrade 请求交由 serveWS 内部记录生命周期）。
func withAccessLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !isWebSocketUpgrade(r) {
			log.Printf("%s %s %s", r.Method, r.URL.RequestURI(), r.RemoteAddr)
		}
		next.ServeHTTP(w, r)
	})
}

func isWebSocketUpgrade(r *http.Request) bool {
	return strings.EqualFold(r.Header.Get("Upgrade"), "websocket")
}
