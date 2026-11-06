package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func newTestServer(t *testing.T, ttl time.Duration) (*httptest.Server, *Hub) {
	t.Helper()
	hub := newHub(ttl)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/battle" {
			serveWS(hub, func(*http.Request) bool { return true }).ServeHTTP(w, r)
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	t.Cleanup(srv.Close)
	return srv, hub
}

func dialBattle(t *testing.T, srv *httptest.Server) *websocket.Conn {
	t.Helper()
	u, err := url.Parse(srv.URL)
	if err != nil {
		t.Fatalf("parse url: %v", err)
	}
	u.Scheme = "ws"
	u.Path = "/battle"
	conn, _, err := websocket.DefaultDialer.Dial(u.String(), nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { conn.Close() })
	return conn
}

func writeJSON(t *testing.T, conn *websocket.Conn, m Msg) {
	t.Helper()
	if err := conn.WriteJSON(m); err != nil {
		t.Fatalf("write %s: %v", m.Type, err)
	}
}

func readMsg(t *testing.T, conn *websocket.Conn) Msg {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, data, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	var m Msg
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatalf("unmarshal %q: %v", string(data), err)
	}
	return m
}

// readUntil 持续读取直到出现满足条件的消息，跳过其余。
// 大厅广播（table_update 等）会与应答交错到达，测试用此函数只关心目标消息。
func readUntil(t *testing.T, conn *websocket.Conn, desc string, match func(Msg) bool) Msg {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("waiting for %s: %v", desc, err)
		}
		var m Msg
		if err := json.Unmarshal(data, &m); err != nil {
			t.Fatalf("unmarshal %q: %v", string(data), err)
		}
		if match(m) {
			return m
		}
	}
}

// expectNoMsg 在指定时间内断言不收到某类消息（收到即失败；超时视为通过）。
func expectNoMsg(t *testing.T, conn *websocket.Conn, dur time.Duration, typ string) {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(dur))
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			return // 读超时即通过
		}
		var m Msg
		if json.Unmarshal(data, &m) == nil && m.Type == typ {
			t.Fatalf("unexpected %s", typ)
		}
	}
}

// findEmptyTableNo 返回当前唯一空桌的桌号。
func findEmptyTableNo(t *testing.T, hub *Hub) int {
	t.Helper()
	hub.mu.Lock()
	rooms := make([]*Room, 0, len(hub.lobbyTables))
	for _, r := range hub.lobbyTables {
		rooms = append(rooms, r)
	}
	hub.mu.Unlock()
	for _, r := range rooms {
		if r.tableInfo().Status == tableEmpty {
			return r.tableNo
		}
	}
	t.Fatal("no empty table")
	return 0
}

// 完整主流程：建房→加入→开局→走子→对账→心跳→鉴权失败→认输。
func TestBattleFullFlow(t *testing.T) {
	srv, _ := newTestServer(t, 10*time.Minute)

	// 红方建房
	host := dialBattle(t, srv)
	writeJSON(t, host, Msg{Type: msgCreateRoom})
	created := readMsg(t, host)
	if created.Type != msgRoomCreated || created.Color != colorRed {
		t.Fatalf("expect room_created/red, got %+v", created)
	}
	if len(created.Room) != 6 || created.Token == "" {
		t.Fatalf("bad room created: %+v", created)
	}
	roomCode, hostToken := created.Room, created.Token

	// 错误房间号
	random := dialBattle(t, srv)
	writeJSON(t, random, Msg{Type: msgJoinRoom, Room: "000000"})
	if m := readMsg(t, random); m.Type != msgError || m.Code != errRoomNotFound {
		t.Fatalf("expect room_not_found, got %+v", m)
	}
	random.Close()

	// 黑方加入
	guest := dialBattle(t, srv)
	writeJSON(t, guest, Msg{Type: msgJoinRoom, Room: roomCode})
	joined := readMsg(t, guest)
	if joined.Type != msgRoomJoined || joined.Color != colorBlack {
		t.Fatalf("expect room_joined/black, got %+v", joined)
	}
	guestToken := joined.Token

	// 双方收到 start_game（黑方顺序：room_joined → start_game）
	if m := readMsg(t, guest); m.Type != msgStartGame {
		t.Fatalf("guest expect start_game, got %+v", m)
	}
	if m := readMsg(t, host); m.Type != msgStartGame {
		t.Fatalf("host expect start_game, got %+v", m)
	}

	// 第三方加入应被拒（房间已满）
	third := dialBattle(t, srv)
	writeJSON(t, third, Msg{Type: msgJoinRoom, Room: roomCode})
	if m := readMsg(t, third); m.Type != msgError || m.Code != errRoomFull {
		t.Fatalf("expect room_full, got %+v", m)
	}
	third.Close()

	// 红方走子 → 黑方收到（转发消息不得携带红方 token）
	writeJSON(t, host, Msg{Type: msgMove, Room: roomCode, Token: hostToken, UCCI: "h2e2", Seq: 1})
	mv := readMsg(t, guest)
	if mv.Type != msgMove || mv.UCCI != "h2e2" || mv.Seq != 1 || mv.Token != "" {
		t.Fatalf("bad forwarded move: %+v", mv)
	}

	// 错误 token 必须被拒
	writeJSON(t, host, Msg{Type: msgMove, Room: roomCode, Token: "forged", UCCI: "x", Seq: 2})
	if m := readMsg(t, host); m.Type != msgError || m.Code != errInvalidToken {
		t.Fatalf("expect invalid_token, got %+v", m)
	}

	// 黑方走子 → 红方收到
	writeJSON(t, guest, Msg{Type: msgMove, Room: roomCode, Token: guestToken, UCCI: "h9e7", Seq: 1})
	if m := readMsg(t, host); m.Type != msgMove || m.UCCI != "h9e7" {
		t.Fatalf("host expect black move, got %+v", m)
	}

	// 对账：request_sync / sync_state 双向转发
	writeJSON(t, guest, Msg{Type: msgRequestSync, Room: roomCode, Token: guestToken})
	if m := readMsg(t, host); m.Type != msgRequestSync {
		t.Fatalf("host expect request_sync, got %+v", m)
	}
	writeJSON(t, host, Msg{
		Type: msgSyncState, Room: roomCode, Token: hostToken,
		FEN: "fen-x", Moves: []string{"h2e2", "h9e7"}, Turn: colorRed,
	})
	sync := readMsg(t, guest)
	if sync.Type != msgSyncState || sync.FEN != "fen-x" || len(sync.Moves) != 2 || sync.Turn != colorRed {
		t.Fatalf("bad sync_state: %+v", sync)
	}
	if sync.Token != "" {
		t.Fatalf("sync_state must not leak token: %+v", sync)
	}

	// 应用层 ping 服务器直接回 pong
	writeJSON(t, host, Msg{Type: msgPing, TS: 12345})
	if m := readMsg(t, host); m.Type != msgPong || m.TS != 12345 {
		t.Fatalf("expect pong 12345, got %+v", m)
	}

	// 求和/再来一局：三种求和消息纯转发；new_game 单方意愿同样转发给对方
	// （仲裁达成需双方各发一次，见 TestRematchSwapColors）
	writeJSON(t, host, Msg{Type: msgDrawOffer, Room: roomCode, Token: hostToken})
	if m := readMsg(t, guest); m.Type != msgDrawOffer {
		t.Fatalf("guest expect draw_offer, got %+v", m)
	}
	writeJSON(t, guest, Msg{Type: msgDrawAccept, Room: roomCode, Token: guestToken})
	if m := readMsg(t, host); m.Type != msgDrawAccept {
		t.Fatalf("host expect draw_accept, got %+v", m)
	}
	writeJSON(t, guest, Msg{Type: msgDrawDecline, Room: roomCode, Token: guestToken})
	if m := readMsg(t, host); m.Type != msgDrawDecline {
		t.Fatalf("host expect draw_decline, got %+v", m)
	}
	writeJSON(t, host, Msg{Type: msgNewGame, Room: roomCode, Token: hostToken})
	if m := readMsg(t, guest); m.Type != msgNewGame {
		t.Fatalf("guest expect new_game, got %+v", m)
	}

	// 认输转发
	writeJSON(t, host, Msg{Type: msgResign, Room: roomCode, Token: hostToken})
	if m := readMsg(t, guest); m.Type != msgResign {
		t.Fatalf("guest expect resign, got %+v", m)
	}

	// 未入房连接发对局消息必须被拒
	out := dialBattle(t, srv)
	writeJSON(t, out, Msg{Type: msgMove, UCCI: "h2e2"})
	if m := readMsg(t, out); m.Type != msgError || m.Code != errNotJoined {
		t.Fatalf("expect not_in_room, got %+v", m)
	}
	out.Close()
}

// 再来一局仲裁：单方 new_game 仅转发；双方各发一次后服务端交换先后手
// 并分帧重发 start_game（各带本人颜色）；断线重连恢复的颜色为交换后的值。
func TestRematchSwapColors(t *testing.T) {
	srv, _ := newTestServer(t, 10*time.Minute)

	host := dialBattle(t, srv)
	writeJSON(t, host, Msg{Type: msgCreateRoom})
	created := readMsg(t, host)
	roomCode, hostToken := created.Room, created.Token

	guest := dialBattle(t, srv)
	writeJSON(t, guest, Msg{Type: msgJoinRoom, Room: roomCode})
	joined := readMsg(t, guest)
	guestToken := joined.Token
	_ = readMsg(t, guest) // start_game
	_ = readMsg(t, host)  // start_game

	// 单方意愿：host 发 new_game → 仅转发给 guest，不换色
	writeJSON(t, host, Msg{Type: msgNewGame, Room: roomCode, Token: hostToken})
	if m := readMsg(t, guest); m.Type != msgNewGame {
		t.Fatalf("guest expect forwarded new_game, got %+v", m)
	}

	// guest 也发 new_game：双方意愿达成 → 服务端交换先后手并分帧重发
	writeJSON(t, guest, Msg{Type: msgNewGame, Room: roomCode, Token: guestToken})
	sg := readMsg(t, guest)
	if sg.Type != msgStartGame || sg.Color != colorRed {
		t.Fatalf("guest expect start_game/red (swapped), got %+v", sg)
	}
	sh := readMsg(t, host)
	if sh.Type != msgStartGame || sh.Color != colorBlack {
		t.Fatalf("host expect start_game/black (swapped), got %+v", sh)
	}
	if sg.Red != "" || sg.Black != "" {
		// 老模式无昵称字段：Red/Black 应保持为空（大厅桌才带昵称）
		t.Fatalf("unexpected nick in rematch start_game: %+v", sg)
	}

	// 交换后走子仍按新色转发（host 现执黑、guest 现执红）
	writeJSON(t, host, Msg{Type: msgMove, Room: roomCode, Token: hostToken, UCCI: "h2e2", Seq: 1})
	if m := readMsg(t, guest); m.Type != msgMove || m.UCCI != "h2e2" {
		t.Fatalf("guest expect host move after swap, got %+v", m)
	}

	// host 断线重连：恢复颜色必须是交换后的黑
	host.Close()
	if m := readMsg(t, guest); m.Type != msgOpponentOffline {
		t.Fatalf("guest expect opponent_offline, got %+v", m)
	}
	host2 := dialBattle(t, srv)
	writeJSON(t, host2, Msg{Type: msgJoinRoom, Room: roomCode, Token: hostToken})
	rc := readMsg(t, host2)
	if rc.Type != msgRoomCreated || rc.Color != colorBlack {
		t.Fatalf("host reconnect expect room_created/black (swapped), got %+v", rc)
	}
	if m := readMsg(t, host2); m.Type != msgStartGame {
		t.Fatalf("host reconnect expect start_game resend, got %+v", m)
	}
}

// 断线 → 对手收到 opponent_offline → token 重连 → 双方恢复。
func TestReconnectFlow(t *testing.T) {
	srv, _ := newTestServer(t, 10*time.Minute)

	host := dialBattle(t, srv)
	writeJSON(t, host, Msg{Type: msgCreateRoom})
	created := readMsg(t, host)
	roomCode, hostToken := created.Room, created.Token

	guest1 := dialBattle(t, srv)
	writeJSON(t, guest1, Msg{Type: msgJoinRoom, Room: roomCode})
	joined := readMsg(t, guest1)
	guestToken := joined.Token
	_ = readMsg(t, guest1) // start_game
	_ = readMsg(t, host)   // start_game

	// 黑方断线：红方收到 opponent_offline
	guest1.Close()
	if m := readMsg(t, host); m.Type != msgOpponentOffline {
		t.Fatalf("host expect opponent_offline, got %+v", m)
	}

	// 黑方用原 token 重连：自己收到 room_joined + start_game（已开局补发），红方收到 opponent_online
	guest2 := dialBattle(t, srv)
	writeJSON(t, guest2, Msg{Type: msgJoinRoom, Room: roomCode, Token: guestToken})
	if m := readMsg(t, guest2); m.Type != msgRoomJoined || m.Token != guestToken {
		t.Fatalf("reconnect expect room_joined, got %+v", m)
	}
	if m := readMsg(t, guest2); m.Type != msgStartGame {
		t.Fatalf("reconnect expect start_game resend, got %+v", m)
	}
	if m := readMsg(t, host); m.Type != msgOpponentOnline {
		t.Fatalf("host expect opponent_online, got %+v", m)
	}

	// 伪造 token 重连必须被拒
	attacker := dialBattle(t, srv)
	writeJSON(t, attacker, Msg{Type: msgJoinRoom, Room: roomCode, Token: "deadbeef"})
	if m := readMsg(t, attacker); m.Type != msgError || m.Code != errInvalidToken {
		t.Fatalf("expect invalid_token, got %+v", m)
	}

	// 红方断线后用原 token 重连：收到 room_created（建房者语义），黑方收到上线通知
	host.Close()
	if m := readMsg(t, guest2); m.Type != msgOpponentOffline {
		t.Fatalf("guest expect opponent_offline, got %+v", m)
	}
	host2 := dialBattle(t, srv)
	writeJSON(t, host2, Msg{Type: msgJoinRoom, Room: roomCode, Token: hostToken})
	if m := readMsg(t, host2); m.Type != msgRoomCreated || m.Token != hostToken {
		t.Fatalf("host reconnect expect room_created, got %+v", m)
	}
	if m := readMsg(t, host2); m.Type != msgStartGame {
		t.Fatalf("host reconnect expect start_game resend, got %+v", m)
	}
	if m := readMsg(t, guest2); m.Type != msgOpponentOnline {
		t.Fatalf("guest expect opponent_online, got %+v", m)
	}
}

// 回归：房主建房后切后台断线，对手凭房号先入座开局（start_game 只发给了
// 当时的 nil host），房主重连必须补发 start_game，否则房主端永远停在
// "等待好友加入"、对手独坐对局页互相死等（好友对战线上 BUG）。
func TestHostRejoinAfterGuestSeated(t *testing.T) {
	srv, hub := newTestServer(t, 10*time.Minute)

	host := dialBattle(t, srv)
	writeJSON(t, host, Msg{Type: msgCreateRoom})
	created := readMsg(t, host)
	roomCode, hostToken := created.Room, created.Token

	// 房主连接意外断开（分享房号后切微信被系统断网）
	host.Close()
	time.Sleep(100 * time.Millisecond)
	hub.mu.Lock()
	_, exists := hub.rooms[roomCode]
	hub.mu.Unlock()
	if !exists {
		t.Fatal("pre-start drop should retain room for reconnection")
	}

	// 对手凭房号入座：正常收到 room_joined + start_game，独坐等待房主
	guest := dialBattle(t, srv)
	writeJSON(t, guest, Msg{Type: msgJoinRoom, Room: roomCode})
	if m := readMsg(t, guest); m.Type != msgRoomJoined {
		t.Fatalf("guest expect room_joined, got %+v", m)
	}
	if m := readMsg(t, guest); m.Type != msgStartGame {
		t.Fatalf("guest expect start_game, got %+v", m)
	}

	// 房主重连：先 room_created 恢复身份，紧接补发 start_game 进入对局
	host2 := dialBattle(t, srv)
	writeJSON(t, host2, Msg{Type: msgJoinRoom, Room: roomCode, Token: hostToken})
	if m := readMsg(t, host2); m.Type != msgRoomCreated || m.Token != hostToken {
		t.Fatalf("host reconnect expect room_created, got %+v", m)
	}
	if m := readMsg(t, host2); m.Type != msgStartGame {
		t.Fatalf("host reconnect expect start_game resend, got %+v", m)
	}
	// 在线的黑方同时收到红方上线通知
	if m := readMsg(t, guest); m.Type != msgOpponentOnline {
		t.Fatalf("guest expect opponent_online, got %+v", m)
	}

	// 补发后对局即恢复可用：房主（红）走子正常转发给对手
	writeJSON(t, host2, Msg{Type: msgMove, Room: roomCode, Token: hostToken, UCCI: "h2e2", Seq: 1})
	if m := readMsg(t, guest); m.Type != msgMove || m.UCCI != "h2e2" {
		t.Fatalf("guest expect forwarded move, got %+v", m)
	}
}

// 回归：对手离线期间发出的对局消息（走子/求和/悔棋/认输/新局）必须暂存补投，
// 不再静默丢弃；new_game 作废尚未投递的求和/悔棋请求；对账与聊天消息不缓存。
func TestOfflineMessageQueue(t *testing.T) {
	srv, _ := newTestServer(t, 10*time.Minute)

	host := dialBattle(t, srv)
	writeJSON(t, host, Msg{Type: msgCreateRoom})
	created := readMsg(t, host)
	roomCode, hostToken := created.Room, created.Token

	guest1 := dialBattle(t, srv)
	writeJSON(t, guest1, Msg{Type: msgJoinRoom, Room: roomCode})
	joined := readMsg(t, guest1)
	guestToken := joined.Token
	_ = readMsg(t, guest1) // start_game
	_ = readMsg(t, host)   // start_game

	// 黑方离线，红方继续发送对局消息：应全部入队而非丢弃
	guest1.Close()
	if m := readMsg(t, host); m.Type != msgOpponentOffline {
		t.Fatalf("host expect opponent_offline, got %+v", m)
	}
	writeJSON(t, host, Msg{Type: msgMove, Room: roomCode, Token: hostToken, UCCI: "h2e2", Seq: 1})
	writeJSON(t, host, Msg{Type: msgDrawOffer, Room: roomCode, Token: hostToken})
	writeJSON(t, host, Msg{Type: msgUndoRequest, Room: roomCode, Token: hostToken})
	writeJSON(t, host, Msg{Type: msgNewGame, Room: roomCode, Token: hostToken})
	writeJSON(t, host, Msg{Type: msgChatText, Room: roomCode, Token: hostToken, Text: "hi"})

	// 黑方重连：身份回包与 start_game 之后按序补投 [move, new_game]，
	// 求和/悔棋请求被 new_game 作废，聊天不入队（若出现任何一类即为本用例失败）
	guest2 := dialBattle(t, srv)
	writeJSON(t, guest2, Msg{Type: msgJoinRoom, Room: roomCode, Token: guestToken})
	if m := readMsg(t, guest2); m.Type != msgRoomJoined {
		t.Fatalf("reconnect expect room_joined, got %+v", m)
	}
	if m := readMsg(t, guest2); m.Type != msgStartGame {
		t.Fatalf("reconnect expect start_game resend, got %+v", m)
	}
	if m := readMsg(t, guest2); m.Type != msgMove || m.UCCI != "h2e2" || m.Seq != 1 {
		t.Fatalf("queued expect move h2e2 seq1, got %+v", m)
	}
	if m := readMsg(t, guest2); m.Type != msgNewGame {
		t.Fatalf("queued expect new_game (draw_offer/undo_request superseded), got %+v", m)
	}

	// 队列已清空且恢复直投：红方再走子黑方立即收到，无残留消息插队
	writeJSON(t, host, Msg{Type: msgMove, Room: roomCode, Token: hostToken, UCCI: "e2h2", Seq: 2})
	if m := readMsg(t, guest2); m.Type != msgMove || m.UCCI != "e2h2" {
		t.Fatalf("expect direct forwarded move after drain, got %+v", m)
	}
}

// 一方离开：另一方收到 leave_room 后连接被 4001 关闭，房间销毁。
func TestLeaveClosesRoom(t *testing.T) {
	srv, hub := newTestServer(t, 10*time.Minute)

	host := dialBattle(t, srv)
	writeJSON(t, host, Msg{Type: msgCreateRoom})
	created := readMsg(t, host)
	roomCode := created.Room

	guest := dialBattle(t, srv)
	writeJSON(t, guest, Msg{Type: msgJoinRoom, Room: roomCode})
	_ = readMsg(t, guest) // room_joined
	_ = readMsg(t, guest) // start_game
	_ = readMsg(t, host)  // start_game

	writeJSON(t, host, Msg{Type: msgLeaveRoom, Room: roomCode, Token: created.Token})
	if m := readMsg(t, guest); m.Type != msgLeaveRoom {
		t.Fatalf("guest expect leave_room, got %+v", m)
	}

	// 下一条读取应得到关闭（4001），而不是保持可重连
	_ = guest.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, _, err := guest.ReadMessage()
	if ce, ok := err.(*websocket.CloseError); !ok || ce.Code != closeRoomClosed {
		t.Fatalf("expect close 4001, got %v", err)
	}

	// 房间已从 Hub 删除，原 token 重连失败
	time.Sleep(100 * time.Millisecond)
	hub.mu.Lock()
	_, exists := hub.rooms[roomCode]
	hub.mu.Unlock()
	if exists {
		t.Fatal("room should be deleted after leave")
	}
	again := dialBattle(t, srv)
	writeJSON(t, again, Msg{Type: msgJoinRoom, Room: roomCode, Token: created.Token})
	if m := readMsg(t, again); m.Type != msgError || m.Code != errRoomNotFound {
		t.Fatalf("expect room_not_found, got %+v", m)
	}
}

// 双方都离线且超过保留时长：房间被静默回收，token 重连失败。
func TestRetentionExpiry(t *testing.T) {
	srv, hub := newTestServer(t, 150*time.Millisecond)

	host := dialBattle(t, srv)
	writeJSON(t, host, Msg{Type: msgCreateRoom})
	created := readMsg(t, host)
	roomCode, hostToken := created.Room, created.Token
	host.Close()

	time.Sleep(400 * time.Millisecond)

	hub.mu.Lock()
	_, exists := hub.rooms[roomCode]
	hub.mu.Unlock()
	if exists {
		t.Fatal("room should expire after retention ttl")
	}

	again := dialBattle(t, srv)
	writeJSON(t, again, Msg{Type: msgJoinRoom, Room: roomCode, Token: hostToken})
	if m := readMsg(t, again); m.Type != msgError || m.Code != errRoomNotFound {
		t.Fatalf("expect room_not_found after expiry, got %+v", m)
	}
}

// 一方掉线后对手在线但始终不重连：保留到期时在线方收到 leave_room，
// 连接被 4001 关闭，房间回收（"计时到点取消"）。
func TestRetentionNotifiesOnlinePeer(t *testing.T) {
	// ttl 取秒（整除），需 ≥1s 才能在消息中携带非零 ttl
	srv, hub := newTestServer(t, 1200*time.Millisecond)

	host := dialBattle(t, srv)
	writeJSON(t, host, Msg{Type: msgCreateRoom})
	created := readMsg(t, host)
	roomCode, hostToken := created.Room, created.Token

	guest := dialBattle(t, srv)
	writeJSON(t, guest, Msg{Type: msgJoinRoom, Room: roomCode})
	_ = readMsg(t, guest) // room_joined
	_ = readMsg(t, guest) // start_game
	_ = readMsg(t, host)  // start_game

	// 黑方断线：红方收到 opponent_offline（带保留时长 ttl）
	guest.Close()
	off := readMsg(t, host)
	if off.Type != msgOpponentOffline || off.TTL <= 0 {
		t.Fatalf("expect opponent_offline with ttl, got %+v", off)
	}

	// 超过保留时长未重连：红方收到 leave_room（带原因），随后 4001 关闭
	if m := readMsg(t, host); m.Type != msgLeaveRoom || m.Msg == "" {
		t.Fatalf("host expect leave_room with reason, got %+v", m)
	}
	_ = host.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, _, err := host.ReadMessage()
	if ce, ok := err.(*websocket.CloseError); !ok || ce.Code != closeRoomClosed {
		t.Fatalf("expect close 4001, got %v", err)
	}

	time.Sleep(600 * time.Millisecond)
	hub.mu.Lock()
	_, exists := hub.rooms[roomCode]
	hub.mu.Unlock()
	if exists {
		t.Fatal("room should be deleted after retention expiry")
	}

	again := dialBattle(t, srv)
	writeJSON(t, again, Msg{Type: msgJoinRoom, Room: roomCode, Token: hostToken})
	if m := readMsg(t, again); m.Type != msgError || m.Code != errRoomNotFound {
		t.Fatalf("expect room_not_found after expiry, got %+v", m)
	}
}

// 建房等待阶段：主动 leave_room 立即销毁；意外断线保留 preGameTTL 供重连。
func TestPreStartLeaveAndDrop(t *testing.T) {
	srv, hub := newTestServer(t, 10*time.Minute)

	// 场景1：房主主动 leave_room 取消建房 → 立即销毁
	host := dialBattle(t, srv)
	writeJSON(t, host, Msg{Type: msgCreateRoom})
	created := readMsg(t, host)
	roomCode := created.Room

	writeJSON(t, host, Msg{Type: msgLeaveRoom, Room: roomCode, Token: created.Token})
	time.Sleep(100 * time.Millisecond)
	hub.mu.Lock()
	_, exists := hub.rooms[roomCode]
	hub.mu.Unlock()
	if exists {
		t.Fatal("pre-start leave should destroy room immediately")
	}

	// 场景2：房主建房后连接意外断开（未发 leave_room）→ 保留 preGameTTL 不立即销毁
	host2 := dialBattle(t, srv)
	writeJSON(t, host2, Msg{Type: msgCreateRoom})
	created2 := readMsg(t, host2)
	host2.Close()
	time.Sleep(100 * time.Millisecond)
	hub.mu.Lock()
	_, exists2 := hub.rooms[created2.Room]
	hub.mu.Unlock()
	if !exists2 {
		t.Fatal("pre-start accidental drop should retain room for reconnection")
	}

	// 场景3：建房等待阶段房主断线，新好友仍可加入占座（房主随后重连收到 start_game）
	late := dialBattle(t, srv)
	writeJSON(t, late, Msg{Type: msgJoinRoom, Room: created2.Room})
	joined := readMsg(t, late)
	if joined.Type != msgRoomJoined || joined.Color != colorBlack {
		t.Fatalf("expect guest can join while host temporarily offline, got %+v", joined)
	}
	if m := readMsg(t, late); m.Type != msgStartGame {
		t.Fatalf("guest expect start_game, got %+v", m)
	}
}

// 聊天消息：chat_text / chat_voice 双向透传，负载字段完整，token 被剥离。
func TestChatForwarding(t *testing.T) {
	srv, _ := newTestServer(t, 10*time.Minute)

	host := dialBattle(t, srv)
	writeJSON(t, host, Msg{Type: msgCreateRoom})
	created := readMsg(t, host)
	roomCode, hostToken := created.Room, created.Token

	guest := dialBattle(t, srv)
	writeJSON(t, guest, Msg{Type: msgJoinRoom, Room: roomCode})
	guestToken := readMsg(t, guest).Token
	_ = readMsg(t, guest) // start_game
	_ = readMsg(t, host)  // start_game

	// 红方文字 → 黑方收到原文且无 token
	writeJSON(t, host, Msg{
		Type: msgChatText, Room: roomCode, Token: hostToken,
		Text: "该你走了",
	})
	if m := readMsg(t, guest); m.Type != msgChatText || m.Text != "该你走了" || m.Token != "" {
		t.Fatalf("bad chat_text forward: %+v", m)
	}

	// 黑方语音 → 红方收到完整 base64 数据与时长，且无 token
	const sampleB64 = "IyFBTVItTkJfMDEyMw==" // 仅验证透传，内容无需是合法 AMR
	writeJSON(t, guest, Msg{
		Type: msgChatVoice, Room: roomCode, Token: guestToken,
		Data: sampleB64, Dur: 20000,
	})
	if m := readMsg(t, host); m.Type != msgChatVoice || m.Data != sampleB64 || m.Dur != 20000 || m.Token != "" {
		t.Fatalf("bad chat_voice forward: %+v", m)
	}
}

// 大厅全流程：进大厅→坐满自动开局→第三人观战→token 鉴权→走子三方送达。
func TestLobbyFullFlow(t *testing.T) {
	srv, hub := newTestServer(t, 10*time.Minute)

	// 服务器启动即有一张空桌
	hub.mu.Lock()
	initialTables := len(hub.lobbyTables)
	hub.mu.Unlock()
	if initialTables != 1 {
		t.Fatalf("server boot should keep exactly 1 empty table, got %d", initialTables)
	}

	// 甲进大厅
	a := dialBattle(t, srv)
	writeJSON(t, a, Msg{Type: msgEnterLobby, Nick: "甲"})
	state := readUntil(t, a, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	if len(state.Tables) != 1 || state.Tables[0].Status != tableEmpty {
		t.Fatalf("lobby_state should contain 1 empty table, got %+v", state)
	}
	emptyNo := state.Tables[0].No

	// 乙进大厅
	b := dialBattle(t, srv)
	writeJSON(t, b, Msg{Type: msgEnterLobby, Nick: "乙"})
	_ = readUntil(t, b, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })

	// 甲坐空桌 → 甲收到 sit_ok(red)；乙收到 table_update(waiting, 红=甲) 与新空桌广播
	writeJSON(t, a, Msg{Type: msgSit, Table: emptyNo})
	sitOkA := readUntil(t, a, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk })
	if sitOkA.Color != colorRed || sitOkA.Token == "" || sitOkA.Room == "" {
		t.Fatalf("sit should return red sit_ok with token/room, got %+v", sitOkA)
	}
	if m := readUntil(t, b, "waiting update", func(m Msg) bool {
		return m.Type == msgTableUpdate && m.Info != nil && m.Info.No == emptyNo && m.Info.Status == tableWaiting
	}); m.Info.Red != "甲" {
		t.Fatalf("expect waiting table_update with red nick, got %+v", m)
	}
	if m := readUntil(t, b, "new empty update", func(m Msg) bool {
		return m.Type == msgTableUpdate && m.Info != nil && m.Info.Status == tableEmpty
	}); m.Info.No == emptyNo {
		t.Fatalf("new empty table should differ from occupied one, got %+v", m)
	}

	// 乙坐同一桌 → 自动开局（双方收到带昵称的 start_game）
	writeJSON(t, b, Msg{Type: msgSit, Table: emptyNo})
	sitOkB := readUntil(t, b, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk })
	if sitOkB.Color != colorBlack {
		t.Fatalf("second sit should be black, got %+v", sitOkB)
	}
	if m := readUntil(t, a, "start_game", func(m Msg) bool { return m.Type == msgStartGame }); m.Red != "甲" || m.Black != "乙" {
		t.Fatalf("expect start_game with nicks, got %+v", m)
	}
	if m := readUntil(t, b, "start_game", func(m Msg) bool { return m.Type == msgStartGame }); m.Red != "甲" || m.Black != "乙" {
		t.Fatalf("expect start_game with nicks, got %+v", m)
	}

	// 丙观战（对局进行中，尚无一着）
	c := dialBattle(t, srv)
	writeJSON(t, c, Msg{Type: msgEnterLobby, Nick: "丙"})
	_ = readUntil(t, c, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	writeJSON(t, c, Msg{Type: msgWatch, Table: emptyNo})
	watchOk := readUntil(t, c, "watch_ok", func(m Msg) bool { return m.Type == msgWatchOk })
	if watchOk.Table != emptyNo || watchOk.Red != "甲" || watchOk.Black != "乙" || watchOk.Turn != colorRed || len(watchOk.Moves) != 0 {
		t.Fatalf("bad watch_ok: %+v", watchOk)
	}

	// 伪造 token 走子必须被拒
	writeJSON(t, a, Msg{Type: msgMove, Room: sitOkA.Room, Token: "bad", UCCI: "h2e2", Seq: 1})
	if m := readUntil(t, a, "invalid_token", func(m Msg) bool { return m.Type == msgError }); m.Code != errInvalidToken {
		t.Fatalf("expect invalid_token for forged token, got %+v", m)
	}

	// 甲走子：乙、丙都收到
	writeJSON(t, a, Msg{Type: msgMove, Room: sitOkA.Room, Token: sitOkA.Token, UCCI: "h2e2", Seq: 1})
	if m := readUntil(t, b, "move", func(m Msg) bool { return m.Type == msgMove }); m.UCCI != "h2e2" {
		t.Fatalf("black expect move, got %+v", m)
	}
	if m := readUntil(t, c, "watcher move", func(m Msg) bool { return m.Type == msgMove }); m.UCCI != "h2e2" {
		t.Fatalf("watcher expect move, got %+v", m)
	}
}

// 大厅：空桌唯一性（create_table 已有空桌时拒绝）。
func TestLobbyEmptyTableUnique(t *testing.T) {
	srv, hub := newTestServer(t, 10*time.Minute)

	c := dialBattle(t, srv)
	writeJSON(t, c, Msg{Type: msgEnterLobby, Nick: "甲"})
	_ = readUntil(t, c, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })

	writeJSON(t, c, Msg{Type: msgCreateTable})
	if m := readUntil(t, c, "error", func(m Msg) bool { return m.Type == msgError }); m.Code != errEmptyTableExists {
		t.Fatalf("expect empty_table_exists, got %+v", m)
	}

	hub.mu.Lock()
	n := len(hub.lobbyTables)
	hub.mu.Unlock()
	if n != 1 {
		t.Fatalf("create_table should not create extra table, got %d", n)
	}
}

// 大厅：坐满自动开局 + 旁观者走子缓存/实时消息 + 悔棋撤两着 + 聊天不走旁观席。
func TestLobbyWatchAndMoveCache(t *testing.T) {
	srv, hub := newTestServer(t, 10*time.Minute)

	a := dialBattle(t, srv)
	writeJSON(t, a, Msg{Type: msgEnterLobby, Nick: "甲"})
	_ = readUntil(t, a, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	b := dialBattle(t, srv)
	writeJSON(t, b, Msg{Type: msgEnterLobby, Nick: "乙"})
	_ = readUntil(t, b, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })

	// 甲乙坐满同一空桌
	emptyNo := findEmptyTableNo(t, hub)
	writeJSON(t, a, Msg{Type: msgSit, Table: emptyNo})
	sitOkA := readUntil(t, a, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk })
	if sitOkA.Color != colorRed {
		t.Fatalf("expect red sit_ok, got %+v", sitOkA)
	}
	writeJSON(t, b, Msg{Type: msgSit, Table: emptyNo})
	sitOkB := readUntil(t, b, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk })
	if sitOkB.Color != colorBlack {
		t.Fatalf("expect black sit_ok, got %+v", sitOkB)
	}
	_ = readUntil(t, a, "start_game", func(m Msg) bool { return m.Type == msgStartGame })
	_ = readUntil(t, b, "start_game", func(m Msg) bool { return m.Type == msgStartGame })

	// 先走两步，验证中途观战者的走子缓存
	writeJSON(t, a, Msg{Type: msgMove, Room: sitOkA.Room, Token: sitOkA.Token, UCCI: "h2e2", Seq: 1})
	if m := readUntil(t, b, "move", func(m Msg) bool { return m.Type == msgMove }); m.UCCI != "h2e2" {
		t.Fatalf("black expect move, got %+v", m)
	}
	writeJSON(t, b, Msg{Type: msgMove, Room: sitOkB.Room, Token: sitOkB.Token, UCCI: "h9e7", Seq: 1})
	if m := readUntil(t, a, "move", func(m Msg) bool { return m.Type == msgMove }); m.UCCI != "h9e7" {
		t.Fatalf("red expect move, got %+v", m)
	}

	// 丙中途观战：watch_ok 应带两步缓存，轮到红走
	c := dialBattle(t, srv)
	writeJSON(t, c, Msg{Type: msgEnterLobby, Nick: "丙"})
	_ = readUntil(t, c, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	writeJSON(t, c, Msg{Type: msgWatch, Table: emptyNo})
	watchOk := readUntil(t, c, "watch_ok", func(m Msg) bool { return m.Type == msgWatchOk })
	if len(watchOk.Moves) != 2 || watchOk.Moves[0] != "h2e2" || watchOk.Moves[1] != "h9e7" || watchOk.Turn != colorRed {
		t.Fatalf("watch_ok should cache 2 moves, got %+v", watchOk)
	}

	// 甲再走一步：乙、丙同时收到
	writeJSON(t, a, Msg{Type: msgMove, Room: sitOkA.Room, Token: sitOkA.Token, UCCI: "e2e4", Seq: 2})
	if m := readUntil(t, b, "move", func(m Msg) bool { return m.Type == msgMove }); m.UCCI != "e2e4" {
		t.Fatalf("black expect move, got %+v", m)
	}
	if m := readUntil(t, c, "watcher move", func(m Msg) bool { return m.Type == msgMove }); m.UCCI != "e2e4" {
		t.Fatalf("watcher expect move, got %+v", m)
	}

	// 聊天只发给对局双方，不发给旁观者
	writeJSON(t, a, Msg{Type: msgChatText, Room: sitOkA.Room, Token: sitOkA.Token, Text: "hi"})
	if m := readUntil(t, b, "chat", func(m Msg) bool { return m.Type == msgChatText }); m.Text != "hi" {
		t.Fatalf("black expect chat, got %+v", m)
	}
	expectNoMsg(t, c, 300*time.Millisecond, msgChatText)

	// 甲悔棋：undo_request → undo_accept，观战缓存撤两着
	writeJSON(t, a, Msg{Type: msgUndoRequest, Room: sitOkA.Room, Token: sitOkA.Token})
	_ = readUntil(t, b, "undo_request", func(m Msg) bool { return m.Type == msgUndoRequest })
	writeJSON(t, b, Msg{Type: msgUndoAccept, Room: sitOkB.Room, Token: sitOkB.Token})
	_ = readUntil(t, a, "undo_accept", func(m Msg) bool { return m.Type == msgUndoAccept })

	// 撤两着后缓存剩 1 着（h2e2），轮到黑走
	d := dialBattle(t, srv)
	writeJSON(t, d, Msg{Type: msgEnterLobby, Nick: "丁"})
	_ = readUntil(t, d, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	writeJSON(t, d, Msg{Type: msgWatch, Table: emptyNo})
	watchOk2 := readUntil(t, d, "watch_ok", func(m Msg) bool { return m.Type == msgWatchOk })
	if len(watchOk2.Moves) != 1 || watchOk2.Moves[0] != "h2e2" || watchOk2.Turn != colorBlack {
		t.Fatalf("watch_ok after undo should have 1 move, got %+v", watchOk2)
	}
}

// 大厅：未开局离座释放座位；已开局离座解散整桌（旁观者收 watch_end，大厅收 closed）。
func TestLobbyStand(t *testing.T) {
	srv, hub := newTestServer(t, 10*time.Minute)

	a := dialBattle(t, srv)
	writeJSON(t, a, Msg{Type: msgEnterLobby, Nick: "甲"})
	_ = readUntil(t, a, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })

	emptyNo := findEmptyTableNo(t, hub)
	writeJSON(t, a, Msg{Type: msgSit, Table: emptyNo})
	_ = readUntil(t, a, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk })

	// 未开局离座：座位释放，桌状态回 empty，棋桌不销毁
	writeJSON(t, a, Msg{Type: msgStand, Table: emptyNo})
	if m := readUntil(t, a, "empty update", func(m Msg) bool {
		return m.Type == msgTableUpdate && m.Info != nil && m.Info.No == emptyNo && m.Info.Status == tableEmpty
	}); m.Info.Red != "" {
		t.Fatalf("expect empty table_update after stand, got %+v", m)
	}
	hub.mu.Lock()
	room := hub.lobbyTables[emptyNo]
	hub.mu.Unlock()
	if room == nil || room.isClosed() {
		t.Fatal("pre-start stand should not close table")
	}

	// 重新坐满
	writeJSON(t, a, Msg{Type: msgSit, Table: emptyNo})
	sitOkA := readUntil(t, a, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk })
	if sitOkA.Color != colorRed {
		t.Fatalf("expect red, got %+v", sitOkA)
	}
	b := dialBattle(t, srv)
	writeJSON(t, b, Msg{Type: msgEnterLobby, Nick: "乙"})
	_ = readUntil(t, b, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	writeJSON(t, b, Msg{Type: msgSit, Table: emptyNo})
	_ = readUntil(t, b, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk })
	_ = readUntil(t, a, "start_game", func(m Msg) bool { return m.Type == msgStartGame })
	_ = readUntil(t, b, "start_game", func(m Msg) bool { return m.Type == msgStartGame })

	// 丙观战
	c := dialBattle(t, srv)
	writeJSON(t, c, Msg{Type: msgEnterLobby, Nick: "丙"})
	_ = readUntil(t, c, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	writeJSON(t, c, Msg{Type: msgWatch, Table: emptyNo})
	_ = readUntil(t, c, "watch_ok", func(m Msg) bool { return m.Type == msgWatchOk })

	// 已开局离座：解散整桌；乙收 leave_room，丙收 watch_end，大厅收 closed 广播
	writeJSON(t, a, Msg{Type: msgStand, Table: emptyNo})
	if m := readUntil(t, b, "leave_room", func(m Msg) bool { return m.Type == msgLeaveRoom }); m.Room == "" {
		t.Fatalf("black expect leave_room, got %+v", m)
	}
	if m := readUntil(t, c, "watch_end", func(m Msg) bool { return m.Type == msgWatchEnd }); m.Table != emptyNo {
		t.Fatalf("watcher expect watch_end, got %+v", m)
	}
	if m := readUntil(t, a, "closed update", func(m Msg) bool {
		return m.Type == msgTableUpdate && m.Info != nil && m.Info.No == emptyNo && m.Info.Status == tableClosed
	}); m.Info.No != emptyNo {
		t.Fatalf("expect closed table_update, got %+v", m)
	}

	// 解散后大厅仍恰好一张空桌
	hub.mu.Lock()
	rooms := make([]*Room, 0, len(hub.lobbyTables))
	for _, r := range hub.lobbyTables {
		rooms = append(rooms, r)
	}
	hub.mu.Unlock()
	empties := 0
	for _, r := range rooms {
		if r.tableInfo().Status == tableEmpty {
			empties++
		}
	}
	if empties != 1 {
		t.Fatalf("expect exactly 1 empty table after close, got %d", empties)
	}
}

// TestLobbyRetentionNeverKillsOnlineGame 大厅桌对局中，保留计时器绝不允许把
// 仍在进行的对局强制解散：
//  1. 一方断线、另一方在线（远超 TTL）→ 棋桌存活，断线方凭 token 恢复；
//  2. 双方瞬断（计时器武装）→ 保留窗口内一方重连即撤销计时，对局存活；
//  3. 双方均离线超时 → 棋桌正常回收。
func TestLobbyRetentionNeverKillsOnlineGame(t *testing.T) {
	srv, hub := newTestServer(t, 150*time.Millisecond)

	// 甲乙坐满唯一空桌开局
	a := dialBattle(t, srv)
	writeJSON(t, a, Msg{Type: msgEnterLobby, Nick: "甲"})
	_ = readUntil(t, a, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	tableNo := findEmptyTableNo(t, hub)
	writeJSON(t, a, Msg{Type: msgSit, Table: tableNo})
	sitA := readUntil(t, a, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk })

	b := dialBattle(t, srv)
	writeJSON(t, b, Msg{Type: msgEnterLobby, Nick: "乙"})
	_ = readUntil(t, b, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	writeJSON(t, b, Msg{Type: msgSit, Table: tableNo})
	sitB := readUntil(t, b, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk })
	_ = readUntil(t, a, "start_game", func(m Msg) bool { return m.Type == msgStartGame })
	_ = readUntil(t, b, "start_game", func(m Msg) bool { return m.Type == msgStartGame })

	// 1) 甲断线：乙在线 → 不得计时收桌，等远超 TTL 后房间必须仍在
	a.Close()
	time.Sleep(500 * time.Millisecond)

	a2 := dialBattle(t, srv)
	writeJSON(t, a2, Msg{Type: msgJoinRoom, Room: sitA.Room, Token: sitA.Token})
	if m := readUntil(t, a2, "host rejoin", func(m Msg) bool {
		return m.Type == msgRoomCreated || m.Type == msgRoomJoined
	}); m.Token != sitA.Token {
		t.Fatalf("host rejoin should restore token, got %+v", m)
	}
	_ = readUntil(t, a2, "start_game after rejoin", func(m Msg) bool { return m.Type == msgStartGame })

	// 2) 双方瞬断：计时器武装（TTL=150ms），乙在保留窗口内重连必须成功
	a2.Close()
	b.Close()
	time.Sleep(50 * time.Millisecond)

	b2 := dialBattle(t, srv)
	writeJSON(t, b2, Msg{Type: msgJoinRoom, Room: sitB.Room, Token: sitB.Token})
	if m := readUntil(t, b2, "guest rejoin", func(m Msg) bool {
		return m.Type == msgRoomJoined || m.Type == msgRoomCreated
	}); m.Token != sitB.Token {
		t.Fatalf("guest rejoin should restore token, got %+v", m)
	}
	_ = readUntil(t, b2, "start_game after guest rejoin", func(m Msg) bool { return m.Type == msgStartGame })

	// 重连已撤销计时器：再等远超 TTL，棋桌必须仍然存活（乙仍在线）
	time.Sleep(500 * time.Millisecond)
	a3 := dialBattle(t, srv)
	writeJSON(t, a3, Msg{Type: msgJoinRoom, Room: sitA.Room, Token: sitA.Token})
	if m := readUntil(t, a3, "host rejoin again", func(m Msg) bool {
		return m.Type == msgRoomCreated || m.Type == msgRoomJoined
	}); m.Token != sitA.Token {
		t.Fatalf("table should survive after rejoin disarms retention, got %+v", m)
	}

	// 3) 双方都离线且不再重连 → 超时后棋桌回收
	a3.Close()
	b2.Close()
	time.Sleep(500 * time.Millisecond)
	hub.mu.Lock()
	_, alive := hub.lobbyTables[tableNo]
	hub.mu.Unlock()
	if alive {
		t.Fatalf("table %d should be destroyed after both players offline past TTL", tableNo)
	}
}

// TestStandAfterGameKeepsTable 终局后离座（stand 带 after_game 标记）：
// 只释放离座方座位、棋桌保留为等待态，对方收到 opponent_left 且座位不变；
// 新对手坐空位后自动重新开局。
func TestStandAfterGameKeepsTable(t *testing.T) {
	srv, hub := newTestServer(t, 10*time.Minute)

	// 甲乙坐满空桌开局（甲=host=红，乙=guest=黑）
	a := dialBattle(t, srv)
	writeJSON(t, a, Msg{Type: msgEnterLobby, Nick: "甲"})
	_ = readUntil(t, a, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	tableNo := findEmptyTableNo(t, hub)
	writeJSON(t, a, Msg{Type: msgSit, Table: tableNo})
	_ = readUntil(t, a, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk })

	b := dialBattle(t, srv)
	writeJSON(t, b, Msg{Type: msgEnterLobby, Nick: "乙"})
	_ = readUntil(t, b, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	writeJSON(t, b, Msg{Type: msgSit, Table: tableNo})
	_ = readUntil(t, b, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk })
	_ = readUntil(t, a, "start_game", func(m Msg) bool { return m.Type == msgStartGame })
	_ = readUntil(t, b, "start_game", func(m Msg) bool { return m.Type == msgStartGame })

	// 甲终局后离座：乙先收 opponent_left（棋桌保留），随后大厅广播 waiting
	// （甲座位已清空、乙座位保留）；甲作为大厅订阅者同样收到该广播
	writeJSON(t, a, Msg{Type: msgStand, Table: tableNo, AfterGame: true})
	if m := readUntil(t, b, "opponent_left", func(m Msg) bool { return m.Type == msgOpponentLeft }); m.Table != tableNo {
		t.Fatalf("black expect opponent_left, got %+v", m)
	}
	for _, conn := range []*websocket.Conn{a, b} {
		if m := readUntil(t, conn, "waiting update", func(m Msg) bool {
			return m.Type == msgTableUpdate && m.Info != nil && m.Info.No == tableNo && m.Info.Status == tableWaiting
		}); m.Info.Red != "" || m.Info.Black != "乙" {
			t.Fatalf("leaver seat should be released while kept seat preserved, got %+v", m)
		}
	}

	// 棋桌必须仍在且未销毁
	hub.mu.Lock()
	room := hub.lobbyTables[tableNo]
	hub.mu.Unlock()
	if room == nil || room.isClosed() {
		t.Fatal("after-game stand should keep table alive")
	}

	// 新对手丁坐空位 → 自动重新开局（丁执红补位，乙黑方座位不变）
	d := dialBattle(t, srv)
	writeJSON(t, d, Msg{Type: msgEnterLobby, Nick: "丁"})
	_ = readUntil(t, d, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	writeJSON(t, d, Msg{Type: msgSit, Table: tableNo})
	if m := readUntil(t, d, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk }); m.Color != colorRed {
		t.Fatalf("new player should take released red seat, got %+v", m)
	}
	if m := readUntil(t, d, "start_game", func(m Msg) bool { return m.Type == msgStartGame }); m.Red != "丁" || m.Black != "乙" {
		t.Fatalf("expect new start_game with nicks, got %+v", m)
	}
	if m := readUntil(t, b, "start_game again", func(m Msg) bool { return m.Type == msgStartGame }); m.Red != "丁" || m.Black != "乙" {
		t.Fatalf("kept seat should receive new start_game, got %+v", m)
	}
}

// 再来一局达成时一方离线：新局 start_game 必须放入该席位的 pending，
// 重连后先收到 room_created/room_joined（带交换后的执色），随后收到
// 带执色与代数的权威开局帧，且不得重复补发同一局的开局帧。
func TestRematchStartPendingForOfflineSeat(t *testing.T) {
	srv, _ := newTestServer(t, 10*time.Minute)

	host := dialBattle(t, srv)
	writeJSON(t, host, Msg{Type: msgCreateRoom})
	created := readMsg(t, host)
	roomCode, hostToken := created.Room, created.Token

	guest := dialBattle(t, srv)
	writeJSON(t, guest, Msg{Type: msgJoinRoom, Room: roomCode})
	joined := readMsg(t, guest)
	guestToken := joined.Token
	_ = readMsg(t, guest) // start_game
	_ = readMsg(t, host)  // start_game

	// host 先表达再来一局意愿，随后断线
	writeJSON(t, host, Msg{Type: msgNewGame, Room: roomCode, Token: hostToken})
	if m := readMsg(t, guest); m.Type != msgNewGame {
		t.Fatalf("guest expect forwarded new_game, got %+v", m)
	}
	host.Close()
	if m := readMsg(t, guest); m.Type != msgOpponentOffline {
		t.Fatalf("guest expect opponent_offline, got %+v", m)
	}

	// 在线方也表达意愿：达成换色；host 离线 → 其开局帧只能进 pending
	writeJSON(t, guest, Msg{Type: msgNewGame, Room: roomCode, Token: guestToken})
	sg := readMsg(t, guest)
	if sg.Type != msgStartGame || sg.Color != colorRed || sg.Gen != 2 {
		t.Fatalf("guest expect start_game/red gen=2, got %+v", sg)
	}

	// host 重连：先 room_created/black，再从 pending 收到 black/gen=2 的开局帧
	host2 := dialBattle(t, srv)
	writeJSON(t, host2, Msg{Type: msgJoinRoom, Room: roomCode, Token: hostToken})
	rc := readMsg(t, host2)
	if rc.Type != msgRoomCreated || rc.Color != colorBlack {
		t.Fatalf("host reconnect expect room_created/black, got %+v", rc)
	}
	st := readMsg(t, host2)
	if st.Type != msgStartGame || st.Color != colorBlack || st.Gen != 2 {
		t.Fatalf("host reconnect expect pending start_game/black gen=2, got %+v", st)
	}
	// 同一局的开局帧不得重复补发（重复会让客户端多触发一次换局/代数漂移）
	expectNoMsg(t, host2, 300*time.Millisecond, msgStartGame)
	if m := readMsg(t, guest); m.Type != msgOpponentOnline {
		t.Fatalf("guest expect opponent_online, got %+v", m)
	}
}

// 换色后的桌子在赛后一方离座：席位色回到默认（host=红），
// 新对手默认坐红，留下的席位按其座位重新落位昵称与执色。
func TestSitAfterRematchUsesResetSeatColor(t *testing.T) {
	srv, hub := newTestServer(t, 10*time.Minute)

	a := dialBattle(t, srv)
	writeJSON(t, a, Msg{Type: msgEnterLobby, Nick: "甲"})
	_ = readUntil(t, a, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	tableNo := findEmptyTableNo(t, hub)
	writeJSON(t, a, Msg{Type: msgSit, Table: tableNo})
	sitA := readUntil(t, a, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk })

	b := dialBattle(t, srv)
	writeJSON(t, b, Msg{Type: msgEnterLobby, Nick: "乙"})
	_ = readUntil(t, b, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	writeJSON(t, b, Msg{Type: msgSit, Table: tableNo})
	sitB := readUntil(t, b, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk })
	_ = readUntil(t, a, "start_game", func(m Msg) bool { return m.Type == msgStartGame })
	_ = readUntil(t, b, "start_game", func(m Msg) bool { return m.Type == msgStartGame })

	// 再来一局：甲先乙后 → 服务端翻转先后手（甲=黑、乙=红，gen=2）
	writeJSON(t, a, Msg{Type: msgNewGame, Room: sitA.Room, Token: sitA.Token})
	_ = readUntil(t, b, "forwarded new_game", func(m Msg) bool { return m.Type == msgNewGame })
	writeJSON(t, b, Msg{Type: msgNewGame, Room: sitB.Room, Token: sitB.Token})
	if m := readUntil(t, b, "rematch start guest", func(m Msg) bool { return m.Type == msgStartGame }); m.Color != colorRed || m.Gen != 2 {
		t.Fatalf("guest expect start_game/red gen=2, got %+v", m)
	}
	if m := readUntil(t, a, "rematch start host", func(m Msg) bool { return m.Type == msgStartGame }); m.Color != colorBlack || m.Gen != 2 {
		t.Fatalf("host expect start_game/black gen=2, got %+v", m)
	}

	// 终局后甲（host，现执黑）离座：桌子回到等待态，席位色复位为 host=红
	writeJSON(t, a, Msg{Type: msgStand, Table: tableNo, AfterGame: true})
	_ = readUntil(t, b, "opponent_left", func(m Msg) bool { return m.Type == msgOpponentLeft })

	// 新对手丁坐回空出的 host 座位：默认执红；留下的乙（guest 座位）执黑
	d := dialBattle(t, srv)
	writeJSON(t, d, Msg{Type: msgEnterLobby, Nick: "丁"})
	_ = readUntil(t, d, "lobby_state", func(m Msg) bool { return m.Type == msgLobbyState })
	writeJSON(t, d, Msg{Type: msgSit, Table: tableNo})
	if m := readUntil(t, d, "sit_ok", func(m Msg) bool { return m.Type == msgSitOk }); m.Color != colorRed {
		t.Fatalf("new player should default to red after seat reset, got %+v", m)
	}
	if m := readUntil(t, d, "start_game", func(m Msg) bool { return m.Type == msgStartGame }); m.Color != colorRed || m.Red != "丁" || m.Black != "乙" || m.Gen != 3 {
		t.Fatalf("new host expect start_game/red gen=3 with nicks, got %+v", m)
	}
	if m := readUntil(t, b, "start_game again", func(m Msg) bool { return m.Type == msgStartGame }); m.Color != colorBlack || m.Red != "丁" || m.Black != "乙" || m.Gen != 3 {
		t.Fatalf("kept guest expect start_game/black gen=3 with nicks, got %+v", m)
	}
}
