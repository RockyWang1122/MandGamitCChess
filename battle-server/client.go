package main

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 90 * time.Second
	pingPeriod     = 50 * time.Second // 必须小于 pongWait
	sendBufferSize = 64
	maxMessageSize = 256 * 1024 // 对局 JSON 均很小；聊天语音为 AMR base64（20 秒约 44KB，256KB 可容纳约 60 秒）
)

// Client 对应一条 WebSocket 连接。重连不复用 Client 对象：
// 每次连接新建 Client，通过 room token 绑定到房间的红/黑位置。
type Client struct {
	conn *websocket.Conn
	hub  *Hub
	send chan []byte

	// 有序关闭请求（容量 1）：写泵排空 send 中已排队的文本帧后再发 close，
	// 保证对端先收到 leave_room 等通知帧，再收到关闭帧。
	gracefulClose chan closeReq

	// 席位绑定：seatMu 保护的字段。旧版流程中 bind 后不再变更；
	// 大厅模式存在跨 goroutine 的 unbind（对局解散由对方连接触发），必须加锁。
	seatMu    sync.RWMutex
	room      *Room
	color     string
	token     string
	watchRoom *Room // 正在观战的棋桌（未观战为 nil）

	// 以下字段仅本连接的 readPump 访问（dispatch 路径），无并发写。
	nick    string // 昵称
	inLobby bool   // 是否订阅大厅广播
}

type closeReq struct {
	code   int
	reason string
}

func newClient(conn *websocket.Conn, hub *Hub) *Client {
	return &Client{
		conn:          conn,
		hub:           hub,
		send:          make(chan []byte, sendBufferSize),
		gracefulClose: make(chan closeReq, 1),
	}
}

func (c *Client) bind(r *Room, color, token string) {
	c.seatMu.Lock()
	c.room = r
	c.color = color
	c.token = token
	c.seatMu.Unlock()
}

// unbind 解除房间绑定（大厅模式：离座/棋桌解散后连接保持存活）。
func (c *Client) unbind() {
	c.seatMu.Lock()
	c.room = nil
	c.color = ""
	c.token = ""
	c.seatMu.Unlock()
}

// getRoom/getColor/getToken/getWatchRoom 读取绑定信息（seatMu 保护）。
func (c *Client) getRoom() *Room {
	c.seatMu.RLock()
	defer c.seatMu.RUnlock()
	return c.room
}

func (c *Client) getColor() string {
	c.seatMu.RLock()
	defer c.seatMu.RUnlock()
	return c.color
}

func (c *Client) getToken() string {
	c.seatMu.RLock()
	defer c.seatMu.RUnlock()
	return c.token
}

func (c *Client) getWatchRoom() *Room {
	c.seatMu.RLock()
	defer c.seatMu.RUnlock()
	return c.watchRoom
}

func (c *Client) setWatchRoom(r *Room) {
	c.seatMu.Lock()
	c.watchRoom = r
	c.seatMu.Unlock()
}

// enqueue 非阻塞投递：缓冲满说明对端严重卡顿，直接丢弃该连接。
func (c *Client) enqueue(b []byte) {
	select {
	case c.send <- b:
	default:
		log.Printf("client %s send buffer full, closing", c.conn.RemoteAddr())
		c.shutdown()
	}
}

func (c *Client) replyError(code, text string) {
	c.enqueue(mustJSON(errorMsg(code, text)))
}

// requestClose 请求写泵在排空已排队文本帧后发送 close 帧。
// 必须用它而非直接 WriteControl，否则 close 帧可能抢先于 leave_room 到达对端
// （WebSocket 规定收到 close 后的数据帧将被忽略）。
func (c *Client) requestClose(code int, reason string) {
	select {
	case c.gracefulClose <- closeReq{code: code, reason: reason}:
	default: // 已有待发关闭请求，忽略重复请求
	}
}

// shutdown 关闭底层 socket，读写泵随即退出。
func (c *Client) shutdown() {
	_ = c.conn.Close()
}

// markReplacedAndShutdown 处理重连时的旧幽灵连接：
// 旧连接读泵退出时因已不在房间槽位上而不会触发下线通知。
func (c *Client) markReplacedAndShutdown() {
	// 槽位已被 hub.join 替换，旧 Client 自然无法在 onClientGone 中匹配，
	// 这里只需关闭其 socket 释放资源。
	c.shutdown()
}

// readPump 在 serveWS 所在 goroutine 阻塞执行。
func (c *Client) readPump() {
	defer func() {
		if r := c.getRoom(); r != nil {
			r.onClientGone(c)
		}
		c.hub.lobbyGone(c) // 大厅订阅与观战席清理
		_ = c.conn.Close()
	}()

	c.conn.SetReadLimit(maxMessageSize)
	_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		return c.conn.SetReadDeadline(time.Now().Add(pongWait))
	})

	for {
		_, data, err := c.conn.ReadMessage()
		if err != nil {
			return
		}
		_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))

		var m Msg
		if err := json.Unmarshal(data, &m); err != nil {
			c.replyError(errBadMessage, "消息格式错误")
			continue
		}
		c.dispatch(m)
	}
}

// writePump 单写者：所有文本帧经 send 通道串行写出，外加周期性协议层 ping。
func (c *Client) writePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		_ = c.conn.Close()
	}()

	for {
		select {
		case b, ok := <-c.send:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				_ = c.conn.WriteControl(websocket.CloseMessage,
					websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""),
					time.Now().Add(writeWait))
				return
			}
			if err := c.conn.WriteMessage(websocket.TextMessage, b); err != nil {
				return
			}
		case req := <-c.gracefulClose:
			// 排空已排队的文本帧（leave_room 必先于本请求入队），再发 close，
			// 保证对端按"通知帧 → 关闭帧"的顺序收到
			if !c.drainSend() {
				return
			}
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteControl(websocket.CloseMessage,
				websocket.FormatCloseMessage(req.code, req.reason),
				time.Now().Add(writeWait)); err != nil {
				return
			}
			// close 帧已发出，稍后由读泵收到对端 close echo 后收尾；
			// 此处直接退出（defer 关闭 socket）
			return
		case <-ticker.C:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteControl(websocket.PingMessage, nil,
				time.Now().Add(writeWait)); err != nil {
				return
			}
		}
	}
}

// drainSend 非阻塞写出 send 通道中所有已排队消息；任一写失败返回 false。
func (c *Client) drainSend() bool {
	for {
		select {
		case b := <-c.send:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.TextMessage, b); err != nil {
				return false
			}
		default:
			return true
		}
	}
}

// dispatch 处理一条已解析消息。
func (c *Client) dispatch(m Msg) {
	// 房间管理类消息（未绑定连接的首条消息）
	switch m.Type {
	case msgCreateRoom:
		if c.getRoom() != nil {
			c.replyError(errAlreadyInRoom, "当前连接已在房间中")
			return
		}
		code, token := c.hub.createRoom(c, m.WantRoom)
		c.enqueue(mustJSON(Msg{
			Type: msgRoomCreated, Room: code, Token: token, Color: colorRed,
		}))
		return

	case msgJoinRoom:
		if c.getRoom() != nil {
			c.replyError(errAlreadyInRoom, "当前连接已在房间中")
			return
		}
		if len(m.Room) != 6 {
			c.replyError(errRoomNotFound, "房间号必须是 6 位")
			return
		}
		c.hub.join(c, m)
		return

	case msgPing:
		// 服务器直接回应用层心跳，不转发
		c.enqueue(mustJSON(Msg{Type: msgPong, TS: m.TS}))
		return

	// ---- 大厅（棋桌）模式消息：不要求已绑定房间 ----
	case msgEnterLobby:
		c.hub.enterLobby(c, m.Nick)
		return
	case msgLeaveLobby:
		c.hub.leaveLobby(c)
		return
	case msgCreateTable:
		c.hub.createTable(c)
		return
	case msgSit:
		c.hub.sit(c, m)
		return
	case msgStand:
		c.hub.stand(c, m.Table, m.AfterGame)
		return
	case msgWatch:
		c.hub.watch(c, m.Table)
		return
	case msgUnwatch:
		c.hub.unwatch(c, m.Table)
		return
	case msgNickUpdate:
		c.hub.nickUpdate(c, m.Nick)
		return
	}

	// 以下均为房间内消息：必须已绑定且 token 正确（防猜号旁观）
	room := c.getRoom()
	if room == nil {
		c.replyError(errNotJoined, "请先创建或加入房间")
		return
	}
	if m.Token != c.getToken() {
		c.replyError(errInvalidToken, "房间令牌无效")
		return
	}

	switch m.Type {
	case msgLeaveRoom:
		room.hub.teardown(room, c)

	// 影响棋局进程的对局消息：对手离线时暂存待投递队列，其重连后按序补投
	// （走子、悔棋/求和协商及应答、认输——丢失任何一条都会造成双方局面或认知分叉）
	case msgMove, msgResign,
		msgDrawOffer, msgDrawAccept, msgDrawDecline,
		msgUndoRequest, msgUndoAccept, msgUndoDecline:
		if !room.isStarted() {
			c.replyError(errNotStarted, "对局尚未开始")
			return
		}
		peer := room.peerOf(c)
		// 重新编码剥掉 token，避免把一方令牌转发给另一方；
		// 其余字段（含未来预留的 timeLeft）原样保留。
		m.Token = ""
		payload := mustJSON(m)
		if peer == nil {
			// 对手离线：消息入队到对手席位的待投递队列，其重连后按序补投
			room.enqueuePending(opponentColorOf(c.getColor()), m.Type, payload)
		} else {
			peer.enqueue(payload)
		}
		// 大厅桌：维护走子缓存并转发旁观者
		room.trackGameMessage(m.Type, m.UCCI, c.getColor(), payload)

	// new_game 不再直接转发：服务端仲裁"再来一局"——双方意愿达成后统一
	// 交换先后手并分帧重发 start_game（见 Room.requestRematch）
	case msgNewGame:
		if !room.isStarted() {
			c.replyError(errNotStarted, "对局尚未开始")
			return
		}
		room.requestRematch(c)

	// 对账与聊天消息不缓存：棋盘恢复由补投队列 + 重连方 request_sync 对账完成
	// （sync_state 是"当下快照"，缓存旧快照反而有害）；聊天丢失不影响棋局。
	// 旁观者不收到私聊与对账消息（防泄露）。
	case msgRequestSync, msgSyncState, msgChatText, msgChatVoice:
		if !room.isStarted() {
			c.replyError(errNotStarted, "对局尚未开始")
			return
		}
		peer := room.peerOf(c)
		if peer == nil {
			return
		}
		m.Token = ""
		peer.enqueue(mustJSON(m))

	default:
		// 未知类型：宽容忽略（向前兼容新增消息）
		log.Printf("room %s ignore unknown message type=%q", room.code, m.Type)
	}
}

// upgrader 与 HTTP 入口在 main.go 中提供，测试复用同一 upgrade 函数。
func serveWS(hub *Hub, checkOrigin func(*http.Request) bool) http.HandlerFunc {
	upgrader := websocket.Upgrader{
		HandshakeTimeout: 5 * time.Second,
		CheckOrigin:      checkOrigin,
	}
	return func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			// Upgrade 内部已写过错误响应
			return
		}
		client := newClient(conn, hub)
		go client.writePump()
		client.readPump()
	}
}
