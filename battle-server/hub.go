package main

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"sort"
	"sync"
	"time"
)

// Hub 管理全部活跃房间与大厅棋桌。
type Hub struct {
	mu    sync.Mutex
	rooms map[string]*Room // 按房间号索引（含大厅桌与旧版房）

	roomTTL    time.Duration // 对局中房间内无人在线后的保留时长（默认 10 分钟）
	preGameTTL time.Duration // 建房等待阶段意外断线后的保留时长（默认 30 秒）

	// ---- 大厅（棋桌）模式 ----
	lobbyMu    sync.Mutex          // 专用锁：大厅状态变更串行化
	lobbyClients map[*Client]bool  // 订阅大厅广播的客户端
	lobbyTables  map[int]*Room     // 按桌号索引（仅含大厅桌）
}

func newHub(ttl time.Duration) *Hub {
	preGame := 60 * time.Second
	if ttl < preGame {
		preGame = ttl
	}
	h := &Hub{
		rooms:        make(map[string]*Room),
		roomTTL:      ttl,
		preGameTTL:   preGame,
		lobbyClients: make(map[*Client]bool),
		lobbyTables:  make(map[int]*Room),
	}
	// 大厅启动即保留一张空桌，无需 30 秒等待
	h.lobbyMu.Lock()
	h.createLobbyRoomLocked()
	h.lobbyMu.Unlock()
	return h
}

// createRoom 执行建房：分配 6 位数字房间号与一次性 token，建房者执红。
// wantRoom 非空时优先使用该号码（已被占用则回退随机分配）。
func (h *Hub) createRoom(c *Client, wantRoom string) (string, string) {
	token := randToken()
	r := &Room{
		hub:          h,
		hostToken:    token,
		hostPlaysRed: true, // 入座默认 host 执红
		createdAt:    time.Now(),
	}
	r.host = c
	c.bind(r, colorRed, token)

	h.mu.Lock()
	if wantRoom != "" {
		// 房主请求固定房号：未被占用则直接使用
		if _, exists := h.rooms[wantRoom]; !exists {
			r.code = wantRoom
		} else {
			r.code = h.allocCodeLocked()
		}
	} else {
		r.code = h.allocCodeLocked()
	}
	h.rooms[r.code] = r
	h.mu.Unlock()

	log.Printf("room %s created by %s (want=%q)", r.code, c.conn.RemoteAddr(), wantRoom)
	return r.code, token
}

// join 处理加入房间。
//   - 不带 token：全新加入（只能占黑方位置，且红方必须在线）；
//     加入成功后向双方下发 start_game。
//   - 带 token：断线重连身份恢复（红方回 room_created，黑方回 room_joined），
//     同时通知在线的对手 opponent_online。
func (h *Hub) join(c *Client, m Msg) {
	h.mu.Lock()
	r := h.rooms[m.Room]
	h.mu.Unlock()
	if r == nil {
		c.replyError(errRoomNotFound, "房间不存在或已关闭")
		return
	}

	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		c.replyError(errRoomNotFound, "房间不存在或已关闭")
		return
	}

	// 大厅棋桌：禁止不带 token 的 join_room 抢座，入座必须走 sit 流程；
	// 断线重连仍走 token 恢复路径，与旧版完全一致。
	if r.isLobby && m.Token == "" {
		r.mu.Unlock()
		c.replyError(errTableNotFound, "大厅棋桌请通过 sit 入座")
		return
	}

	// ---- 断线重连：token 恢复身份 ----
	// 已开局的对局（started=true）重连后必须补发 start_game：
	// 房主断线期间对手全新入座时，start_game 只发给了当时的 nil host，
	// 不补发会导致房主端永远停在"等待好友加入"、对手独坐对局页互相死等。
	if m.Token != "" {
		switch m.Token {
		case r.hostToken:
			// hostPlaysRed 可能已被"再来一局"仲裁翻转：席位色动态计算
			hostColor := r.seatColorLocked(true)
			ghost := r.host
			r.host = c
			c.bind(r, hostColor, r.hostToken)
			peer := r.guest
			started := r.started
			pending := r.drainPendingLocked(hostColor)
			c.enqueue(mustJSON(Msg{
				Type: msgRoomCreated, Room: r.code, Token: r.hostToken, Color: hostColor,
			}))
			// 重连补发的开局帧必须带本人执色与双方昵称：客户端据此恢复
			// 颜色/昵称；若离线期间已达成"再来一局"，新局帧已在 pending
			// 队首，无需重复补发（重复会再次触发换局）。
			if started && !pendingHasStart(pending) {
				c.enqueue(r.startFrameLocked(hostColor))
			}
			// 补投其离线期间对手发出的对局消息（走子/求和/悔棋/认输/新局等）
			for _, p := range pending {
				c.enqueue(p.data)
			}
			// 席位已恢复在线：保留计时只在对端仍空缺时维持
			peerOnline := peer != nil
			r.disarmRetentionLocked()
			r.mu.Unlock()

			if ghost != nil {
				ghost.markReplacedAndShutdown()
			}
			if peerOnline {
				peer.enqueue(mustJSON(Msg{Type: msgOpponentOnline}))
			} else {
				// 对手席仍空缺：重启保留计时，房间不能被在线方无限占用
				r.armRetention()
			}
			log.Printf("room %s host reconnected: %s", r.code, c.conn.RemoteAddr())
			return

		case r.guestToken:
			// hostPlaysRed 可能已被"再来一局"仲裁翻转：席位色动态计算
			guestColor := r.seatColorLocked(false)
			ghost := r.guest
			r.guest = c
			c.bind(r, guestColor, r.guestToken)
			peer := r.host
			started := r.started
			pending := r.drainPendingLocked(guestColor)
			c.enqueue(mustJSON(Msg{
				Type: msgRoomJoined, Room: r.code, Token: r.guestToken, Color: guestColor,
			}))
			if started && !pendingHasStart(pending) {
				c.enqueue(r.startFrameLocked(guestColor))
			}
			// 补投其离线期间对手发出的对局消息（走子/求和/悔棋/认输/新局等）
			for _, p := range pending {
				c.enqueue(p.data)
			}
			peerOnline := peer != nil
			r.disarmRetentionLocked()
			r.mu.Unlock()

			if ghost != nil {
				ghost.markReplacedAndShutdown()
			}
			if peerOnline {
				peer.enqueue(mustJSON(Msg{Type: msgOpponentOnline}))
			} else {
				r.armRetention()
			}
			log.Printf("room %s guest reconnected: %s", r.code, c.conn.RemoteAddr())
			return

		default:
			r.mu.Unlock()
			c.replyError(errInvalidToken, "房间令牌无效")
			return
		}
	}

	// ---- 全新加入：占黑方位置 ----
	if r.host == nil && r.started {
		// 对局已开始但房主断线：提示稍后再试
		r.mu.Unlock()
		c.replyError(errRoomNotReady, "房主不在线，请稍后再试")
		return
	}
	if r.guest != nil {
		// 建房等待阶段允许对方先占座（房主可能切后台分享房号）
		r.mu.Unlock()
		c.replyError(errRoomFull, "房间已有两位玩家")
		return
	}

	token := randToken()
	r.guestToken = token
	r.guest = c
	guestColor := r.seatColorLocked(false)
	hostColor := r.seatColorLocked(true)
	c.bind(r, guestColor, token)
	r.started = true
	r.gameEpoch++
	host := r.host
	r.disarmRetentionLocked()
	// 权威开局帧：各带本人执色与双方昵称（房主离线时其帧由重连路径补发）
	joinedFrame := mustJSON(Msg{
		Type: msgRoomJoined, Room: r.code, Token: token, Color: guestColor,
	})
	guestStart := r.startFrameLocked(guestColor)
	hostStart := r.startFrameLocked(hostColor)
	gen := r.gameEpoch
	r.mu.Unlock()

	if host == nil {
		// 房主在等待阶段断线：仍允许对手入座开局（房主重连时由 token
		// 重连路径补发 start_game）；此处重启保留计时，房主始终不回则
		// 到期通知并回收，避免对手独坐的房间被无限占用。
		r.armRetention()
	}

	// 黑方先收到 room_joined 再收到 start_game（颜色按席位动态计算，仲裁换色后正确）
	c.enqueue(joinedFrame)
	c.enqueue(guestStart)
	if host != nil {
		host.enqueue(hostStart)
	}
	log.Printf("room %s joined by %s, game started (gen=%d)", r.code, c.conn.RemoteAddr(), gen)
}

// teardown 终态销毁房间（一方主动离开时调用）。
// 旧版房：通知另一方 leave_room 并用 4001 关闭码断开，回收双方连接。
// 大厅棋桌：不断开连接（大厅是长连），由 lobbyTeardownLocked 处理。
func (h *Hub) teardown(r *Room, leaver *Client) {
	if r.isLobby {
		h.lobbyMu.Lock()
		defer h.lobbyMu.Unlock()
		h.lobbyTeardownLocked(r, leaver)
		return
	}

	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return
	}
	r.closed = true
	r.disarmRetentionLocked()
	host, guest := r.host, r.guest
	r.host, r.guest = nil, nil
	r.mu.Unlock()

	h.mu.Lock()
	delete(h.rooms, r.code)
	h.mu.Unlock()

	var other *Client
	if leaver == host {
		other = guest
	} else {
		other = host
	}
	if other != nil {
		other.enqueue(mustJSON(Msg{Type: msgLeaveRoom, Room: r.code}))
		other.requestClose(closeRoomClosed, "room_closed")
	}
	leaver.requestClose(1000, "bye")

	go func() {
		time.Sleep(500 * time.Millisecond)
		if host != nil {
			host.shutdown()
		}
		if guest != nil {
			guest.shutdown()
		}
	}()
	log.Printf("room %s closed (leave)", r.code)
}

// expireRetention 保留计时到期：删除房间。
// 旧版房：仍有在线端则 4001 关闭，否则静默回收。
// 大厅棋桌：不断开连接，由 lobbyExpireRetentionLocked 处理。
func (h *Hub) expireRetention(r *Room) {
	if r.isLobby {
		h.lobbyMu.Lock()
		defer h.lobbyMu.Unlock()
		h.lobbyExpireRetentionLocked(r)
		return
	}

	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return
	}
	// 防御性核验：双方均在线说明计时器已失真（重连竞态/旧版本残留计时器），
	// 不能把进行中的对局强制关闭。旧版房保留"单方在线超时关闭"特性不受影响。
	if r.host != nil && r.guest != nil {
		r.disarmRetentionLocked()
		r.mu.Unlock()
		log.Printf("room %s retention skipped (both seats online)", r.code)
		return
	}
	r.closed = true
	r.disarmRetentionLocked()
	host, guest := r.host, r.guest
	r.host, r.guest = nil, nil
	r.mu.Unlock()

	h.mu.Lock()
	delete(h.rooms, r.code)
	h.mu.Unlock()

	for _, c := range []*Client{host, guest} {
		if c == nil {
			continue
		}
		c.enqueue(mustJSON(Msg{
			Type: msgLeaveRoom, Room: r.code,
			Msg: "对方长时间离线，对战已结束",
		}))
		c.requestClose(closeRoomClosed, "room_closed")
	}
	go func() {
		time.Sleep(500 * time.Millisecond)
		if host != nil {
			host.shutdown()
		}
		if guest != nil {
			guest.shutdown()
		}
	}()
	log.Printf("room %s closed (retention timeout)", r.code)
}

// ---- 大厅（棋桌）模式专用内部方法（lobbyMu 已持有）----

// lobbyTeardownLocked 处理大厅棋桌的解散：不关闭连接，仅通知并释放绑定。
func (h *Hub) lobbyTeardownLocked(r *Room, leaver *Client) {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return
	}
	r.closed = true
	r.disarmRetentionLocked()
	host, guest := r.host, r.guest
	watchers := r.watcherListLocked()
	code, tableNo := r.code, r.tableNo
	r.host, r.guest = nil, nil
	r.watchers = nil
	r.mu.Unlock()

	h.mu.Lock()
	delete(h.rooms, code)
	delete(h.lobbyTables, tableNo)
	h.mu.Unlock()

	// 通知另一位玩家（不断开连接）
	var other *Client
	if leaver == host {
		other = guest
	} else {
		other = host
	}
	if other != nil {
		other.enqueue(mustJSON(Msg{Type: msgLeaveRoom, Room: code}))
		other.unbind()
	}
	leaver.unbind()

	// 通知旁观者
	endMsg := mustJSON(Msg{Type: msgWatchEnd, Table: tableNo, Msg: "对局已结束"})
	for _, w := range watchers {
		w.enqueue(endMsg)
		w.setWatchRoom(nil)
	}

	h.broadcastTableUpdateLocked(TableInfo{No: tableNo, Status: tableClosed})
	h.ensureEmptyTablesLocked()
	// 诊断：记录发起解散的一端（昵称+来源地址），便于区分"玩家主动退出"与"客户端异常退出"
	leaverNick, leaverAddr := "", ""
	if leaver != nil {
		leaverNick, leaverAddr = leaver.nick, leaver.conn.RemoteAddr().String()
	}
	log.Printf("lobby table %d closed (leave by %q from %s)", tableNo, leaverNick, leaverAddr)
}

// lobbyExpireRetentionLocked 大厅棋桌保留超时：与 lobbyTeardownLocked 相同，但附不同消息。
// 到期前先核验：计时器的前提是"双方席位均空"（armRetention 对任一席主在线的
// 大厅桌不计时）。到期时仍有席主在线，说明前提已被打破——重连竞态或旧版本
// 残留计时器。此时绝不允许把进行中的对局/在线等待强制解散，撤销本次收桌。
func (h *Hub) lobbyExpireRetentionLocked(r *Room) {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return
	}
	if r.host != nil || r.guest != nil {
		r.disarmRetentionLocked()
		r.mu.Unlock()
		log.Printf("lobby table %d retention skipped (seat holder online)", r.tableNo)
		return
	}
	r.closed = true
	r.disarmRetentionLocked()
	host, guest := r.host, r.guest
	watchers := r.watcherListLocked()
	code, tableNo := r.code, r.tableNo
	r.host, r.guest = nil, nil
	r.watchers = nil
	r.mu.Unlock()

	h.mu.Lock()
	delete(h.rooms, code)
	delete(h.lobbyTables, tableNo)
	h.mu.Unlock()

	reason := mustJSON(Msg{
		Type: msgLeaveRoom, Room: code,
		Msg: "对方长时间离线，对战已结束",
	})
	for _, c := range []*Client{host, guest} {
		if c == nil {
			continue
		}
		c.enqueue(reason)
		c.unbind()
	}

	endMsg := mustJSON(Msg{Type: msgWatchEnd, Table: tableNo, Msg: "对方长时间离线，棋桌已关闭"})
	for _, w := range watchers {
		w.enqueue(endMsg)
		w.setWatchRoom(nil)
	}

	h.broadcastTableUpdateLocked(TableInfo{No: tableNo, Status: tableClosed})
	h.ensureEmptyTablesLocked()
	log.Printf("lobby table %d closed (retention timeout)", tableNo)
}

// ---- 大厅（棋桌）模式公开方法 ----

// enterLobby 订阅大厅广播并下发全量桌列表。
func (h *Hub) enterLobby(c *Client, nick string) {
	c.nick = nick

	// 进大厅时保证常驻空桌存在：空桌只存内存，服务端重启后 lobbyTables
	// 为空，若不在此处补建，首个玩家会看到空荡荡的大厅且无任何事件能触发补桌。
	// 锁序与 createTable 一致：lobbyMu → h.mu（ensureEmptyTablesLocked 内部取 h.mu）。
	h.lobbyMu.Lock()
	h.ensureEmptyTablesLocked()
	h.mu.Lock()
	c.inLobby = true
	h.lobbyClients[c] = true
	rooms := make([]*Room, 0, len(h.lobbyTables))
	for _, r := range h.lobbyTables {
		rooms = append(rooms, r)
	}
	h.mu.Unlock()
	h.lobbyMu.Unlock()

	infos := make([]TableInfo, 0, len(rooms))
	for _, r := range rooms {
		infos = append(infos, r.tableInfo())
	}
	// 按桌号升序，保证客户端列表顺序稳定
	sort.Slice(infos, func(i, j int) bool { return infos[i].No < infos[j].No })
	c.enqueue(mustJSON(Msg{Type: msgLobbyState, Tables: infos}))
}

// leaveLobby 退订大厅广播，不影响入座/观战状态。
func (h *Hub) leaveLobby(c *Client) {
	h.mu.Lock()
	c.inLobby = false
	delete(h.lobbyClients, c)
	h.mu.Unlock()
}

// createTable 新建空桌。已有空桌时拒绝（errEmptyTableExists）。
func (h *Hub) createTable(c *Client) {
	if c.getRoom() != nil {
		c.replyError(errAlreadyInRoom, "已在座位上，请先离座")
		return
	}
	if c.getWatchRoom() != nil {
		c.replyError(errAlreadyInRoom, "正在观战，请先退出观战")
		return
	}
	h.lobbyMu.Lock()
	defer h.lobbyMu.Unlock()
	if h.hasEmptyLobbyTableLocked() {
		c.replyError(errEmptyTableExists, "已有空桌，请直接入座")
		return
	}
	h.createLobbyRoomLocked()
	h.ensureEmptyTablesLocked()
}

// sit 入座。可指定颜色，默认先坐红；坐满自动下发 start_game。
func (h *Hub) sit(c *Client, m Msg) {
	if c.getRoom() != nil {
		c.replyError(errAlreadyInRoom, "已在座位上")
		return
	}
	if c.getWatchRoom() != nil {
		c.replyError(errAlreadyInRoom, "正在观战，请先退出观战")
		return
	}

	h.lobbyMu.Lock()
	defer h.lobbyMu.Unlock()

	h.mu.Lock()
	r := h.lobbyTables[m.Table]
	h.mu.Unlock()
	if r == nil || r.isClosed() {
		c.replyError(errTableNotFound, "棋桌不存在或已关闭")
		return
	}

	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		c.replyError(errTableNotFound, "棋桌不存在或已关闭")
		return
	}

	color := m.Color
	if color != "" && color != colorRed && color != colorBlack {
		r.mu.Unlock()
		c.replyError(errBadMessage, "颜色无效")
		return
	}
	if color == "" {
		// 默认配色取席位当前归属色：hostPlaysRed 可能已被"再来一局"翻转，
		// 不能再按"host 座位=红"硬编码，否则 sit_ok 与席位实色相反
		switch {
		case r.hostToken == "":
			color = r.seatColorLocked(true)
		case r.guestToken == "":
			color = r.seatColorLocked(false)
		default:
			r.mu.Unlock()
			c.replyError(errSeatTaken, "座位已满")
			return
		}
	}

	isHost := (color == colorRed) == r.hostPlaysRed
	if isHost {
		if r.hostToken != "" {
			r.mu.Unlock()
			c.replyError(errSeatTaken, "红方座位已被占用")
			return
		}
		r.hostToken = randToken()
		r.host = c
		r.hostNick = c.nick
	} else {
		if r.guestToken != "" {
			r.mu.Unlock()
			c.replyError(errSeatTaken, "黑方座位已被占用")
			return
		}
		r.guestToken = randToken()
		r.guest = c
		r.guestNick = c.nick
	}

	// 坐满即开局（只要双席位 token 均已分配，即使一方断线也照常开局）
	started := false
	if !r.started && r.hostToken != "" && r.guestToken != "" {
		r.started = true
		r.gameEpoch++
		started = true
	}
	// 有席位离线（token 在、连接已断）时保留既有计时，交给重连窗口处理；
	// 只有双方都在线才撤销计时（此后对局不受超时影响）。
	peerOffline := started && (r.host == nil || r.guest == nil)
	if !peerOffline {
		r.disarmRetentionLocked()
	}
	info := r.tableInfoLocked()
	host, guest := r.host, r.guest
	watchers := r.watcherListLocked()
	// 提前提取所有需在 unlock 后仍使用的字段，避免 data race
	token := r.hostToken
	if !isHost {
		token = r.guestToken
	}
	roomCode := r.code
	redNick := r.hostNick
	blackNick := r.guestNick
	epoch := r.gameEpoch
	// 分帧下发：各席位帧都带自己的实色（seatColorLocked），客户端以此设定执子色
	hostFrame := r.startFrameLocked(r.seatColorLocked(true))
	guestFrame := r.startFrameLocked(r.seatColorLocked(false))
	watchFrame := mustJSON(Msg{
		Type: msgStartGame, Room: roomCode, Red: redNick, Black: blackNick, Gen: r.gameEpoch,
	})
	r.mu.Unlock()

	if peerOffline {
		// 对端席位空缺：重启保留计时（大厅桌若仍有席主在线，armRetention 内部
		// 不武装计时，保证在线等待的玩家不会被倒计时踢出）
		r.armRetention()
	}

	c.bind(r, color, token)
	c.enqueue(mustJSON(Msg{
		Type: msgSitOk, Table: m.Table, Color: color, Token: token, Room: roomCode,
	}))

	if started {
		if host != nil {
			host.enqueue(hostFrame)
		}
		if guest != nil {
			guest.enqueue(guestFrame)
		}
		for _, w := range watchers {
			w.enqueue(watchFrame)
		}
		log.Printf("lobby table %d started gen=%d: red=%s black=%s", m.Table, epoch, redNick, blackNick)
	}

	h.broadcastTableUpdateLocked(info)
	h.ensureEmptyTablesLocked()
}

// stand 离座。未开局释放座位；已开局等价解散整桌。
func (h *Hub) stand(c *Client, tableNo int, afterGame bool) {
	r := c.getRoom()
	if r == nil || !r.isLobby || r.tableNo != tableNo {
		c.replyError(errNotJoined, "不在该桌座位上")
		return
	}
	if r.isStarted() {
		if afterGame {
			// 终局后离座：只释放离座方座位，棋桌保留、对方不被踢出对战界面
			h.lobbyMu.Lock()
			h.standAfterGameLocked(r, c)
			h.lobbyMu.Unlock()
			return
		}
		h.lobbyMu.Lock()
		h.lobbyTeardownLocked(r, c)
		h.lobbyMu.Unlock()
		return
	}

	h.lobbyMu.Lock()
	defer h.lobbyMu.Unlock()
	r.mu.Lock()
	if r.host == c {
		r.host = nil
		r.hostToken = ""
		r.hostNick = ""
		r.pendingHost = nil
	} else if r.guest == c {
		r.guest = nil
		r.guestToken = ""
		r.guestNick = ""
		r.pendingGuest = nil
	}
	r.disarmRetentionLocked()
	r.mu.Unlock()
	c.unbind()

	info := r.tableInfo()
	h.broadcastTableUpdateLocked(info)
	h.ensureEmptyTablesLocked()
}

// standAfterGameLocked 终局后离座：仅释放离座方座位并把棋桌转回等待态，
// 对方保持座位与连接继续等待新对手（收到 opponent_left 提示后返回大厅）。
// 调用方持有 h.lobbyMu；r.isStarted() 必须为 true（对局进行过的桌）。
func (h *Hub) standAfterGameLocked(r *Room, leaver *Client) {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return
	}
	r.disarmRetentionLocked()
	var other *Client
	leaverIsHost := r.host == leaver
	if leaverIsHost {
		other = r.guest
	} else if r.guest == leaver {
		other = r.host
	} else {
		// 已被重连替换等场景：座位不属于该连接，仅解绑连接
		r.mu.Unlock()
		leaver.unbind()
		return
	}
	// 昵称槽按执色存放（hostNick=红方昵称/guestNick=黑方昵称），
	// 不能按"host 座位=红"直接取，否则换色后的桌子会清错人的昵称
	leaverNick := r.nickForSeatLocked(leaverIsHost)
	remainIsHost := !leaverIsHost
	remainNick := ""
	if other != nil {
		remainNick = r.nickForSeatLocked(remainIsHost)
	}
	if leaverIsHost {
		r.host = nil
		r.hostToken = ""
		r.pendingHost = nil
	} else {
		r.guest = nil
		r.guestToken = ""
		r.pendingGuest = nil
	}
	r.started = false // 棋局已终局：桌子回到等待态，新对手入座后重新开局
	// 席位色回到入座默认（host 座位=红）。否则"再来一局"换过色的桌子在赛后
	// 有人离座时，新对手的 sit_ok 颜色会与 seatColorLocked 相反（重连端也错位）。
	r.hostPlaysRed = true
	// 按默认色重建昵称槽：留下的席位按其座位重新落位（host 座位=红、guest 座位=黑），
	// 旧局的 pending 一并作废（残留消息按旧色键控，换色后补投即错位）
	r.pendingHost, r.pendingGuest = nil, nil
	r.hostNick, r.guestNick = "", ""
	if other != nil {
		if remainIsHost {
			r.hostNick = remainNick
		} else {
			r.guestNick = remainNick
		}
	}
	r.mu.Unlock()
	leaver.unbind()

	if other != nil {
		other.enqueue(mustJSON(Msg{
			Type: msgOpponentLeft, Room: r.code, Table: r.tableNo,
			Msg: "对方已退出对战，棋桌已保留，可回大厅继续等待新对手",
		}))
	}
	h.broadcastTableUpdateLocked(r.tableInfo())
	log.Printf("lobby table %d opponent left after game (leaver=%q), table kept waiting",
		r.tableNo, leaverNick)
}

// watch 观战（必须是对局进行中的大厅桌）。
func (h *Hub) watch(c *Client, tableNo int) {
	if c.getRoom() != nil {
		c.replyError(errAlreadyInRoom, "已在座位上，无法观战")
		return
	}

	h.lobbyMu.Lock()
	defer h.lobbyMu.Unlock()

	// 换桌观战：自动退出上一桌
	if prevRoom := c.getWatchRoom(); prevRoom != nil {
		h.mu.Lock()
		prev := h.lobbyTables[prevRoom.tableNo]
		h.mu.Unlock()
		if prev != nil {
			prev.removeWatcher(c)
			h.broadcastTableUpdateLocked(prev.tableInfo())
		}
		c.setWatchRoom(nil)
	}

	h.mu.Lock()
	r := h.lobbyTables[tableNo]
	h.mu.Unlock()
	if r == nil || r.isClosed() {
		c.replyError(errTableNotFound, "棋桌不存在或已关闭")
		return
	}

	r.mu.Lock()
	if !r.started {
		r.mu.Unlock()
		c.replyError(errNotStarted, "对局尚未开始，暂不能观战")
		return
	}
	r.addWatcherLocked(c)
	moves := r.movesSnapshotLocked()
	red, black := r.hostNick, r.guestNick
	info := r.tableInfoLocked()
	r.mu.Unlock()

	c.setWatchRoom(r)
	// 依据走子数计算当前回合方（红先黑后）
	turn := colorRed
	if len(moves)%2 == 1 {
		turn = colorBlack
	}
	c.enqueue(mustJSON(Msg{
		Type: msgWatchOk, Table: tableNo, Red: red, Black: black,
		Moves: moves, Turn: turn,
	}))
	h.broadcastTableUpdateLocked(info)
}

// unwatch 结束观战。
func (h *Hub) unwatch(c *Client, tableNo int) {
	h.lobbyMu.Lock()
	defer h.lobbyMu.Unlock()
	r := c.getWatchRoom()
	if r == nil || r.tableNo != tableNo {
		c.replyError(errNotJoined, "未在观战该桌")
		return
	}
	r.removeWatcher(c)
	c.setWatchRoom(nil)
	h.broadcastTableUpdateLocked(r.tableInfo())
}

// nickUpdate 修改昵称。若已入座/观战，同步广播该桌更新。
func (h *Hub) nickUpdate(c *Client, nick string) {
	c.nick = nick

	if r := c.getRoom(); r != nil && r.isLobby {
		r.mu.Lock()
		switch c.getColor() {
		case colorRed:
			r.hostNick = nick
		case colorBlack:
			r.guestNick = nick
		}
		r.mu.Unlock()
		h.broadcastTableUpdateLocked(r.tableInfo())
		return
	}
	if r := c.getWatchRoom(); r != nil {
		h.broadcastTableUpdateLocked(r.tableInfo())
	}
}

// lobbyGone 连接断开时清理大厅订阅与观战席。
func (h *Hub) lobbyGone(c *Client) {
	h.lobbyMu.Lock()
	defer h.lobbyMu.Unlock()

	h.mu.Lock()
	c.inLobby = false
	delete(h.lobbyClients, c)
	h.mu.Unlock()

	if wr := c.getWatchRoom(); wr != nil {
		c.setWatchRoom(nil)
		wr.removeWatcher(c)
		h.broadcastTableUpdateLocked(wr.tableInfo())
	}
}

// broadcastTableUpdateLocked 向大厅全部订阅者广播单桌状态。调用方必须持有 lobbyMu。
func (h *Hub) broadcastTableUpdateLocked(info TableInfo) {
	payload := mustJSON(Msg{Type: msgTableUpdate, Info: &info})
	h.mu.Lock()
	clients := make([]*Client, 0, len(h.lobbyClients))
	for c := range h.lobbyClients {
		clients = append(clients, c)
	}
	h.mu.Unlock()
	for _, c := range clients {
		c.enqueue(payload)
	}
}

// createLobbyRoomLocked 新建空桌并广播。调用方必须持有 lobbyMu。
func (h *Hub) createLobbyRoomLocked() *Room {
	r := &Room{
		hub:          h,
		isLobby:      true,
		hostPlaysRed: true, // 入座默认 host 执红
		watchers:     make(map[*Client]bool),
	}
	h.mu.Lock()
	r.code = h.allocCodeLocked()
	r.tableNo = h.allocateTableNoLocked()
	h.rooms[r.code] = r
	h.lobbyTables[r.tableNo] = r
	h.mu.Unlock()

	h.broadcastTableUpdateLocked(r.tableInfo())
	log.Printf("lobby table %d created (room %s)", r.tableNo, r.code)
	return r
}

// allocateTableNoLocked 返回最小未占用的桌号：桌解散后号段立即回收复用，
// 避免桌号随"入座→离座→补空桌"循环无限增长。调用方必须持有 h.mu。
func (h *Hub) allocateTableNoLocked() int {
	no := 1
	for {
		if _, taken := h.lobbyTables[no]; !taken {
			return no
		}
		no++
	}
}

// hasEmptyLobbyTableLocked 检查是否存在空桌。调用方必须持有 lobbyMu。
func (h *Hub) hasEmptyLobbyTableLocked() bool {
	h.mu.Lock()
	rooms := make([]*Room, 0, len(h.lobbyTables))
	for _, r := range h.lobbyTables {
		rooms = append(rooms, r)
	}
	h.mu.Unlock()
	for _, r := range rooms {
		if r.tableInfo().Status == tableEmpty {
			return true
		}
	}
	return false
}

// ensureEmptyTablesLocked 保证大厅恰好一张空桌：
// 无空桌则创建一张；存在多张空桌则销毁多余（保留桌号最小者）。
func (h *Hub) ensureEmptyTablesLocked() {
	h.mu.Lock()
	rooms := make([]*Room, 0, len(h.lobbyTables))
	for _, r := range h.lobbyTables {
		rooms = append(rooms, r)
	}
	h.mu.Unlock()

	var empties []*Room
	for _, r := range rooms {
		if r.tableInfo().Status == tableEmpty {
			empties = append(empties, r)
		}
	}
	if len(empties) == 0 {
		h.createLobbyRoomLocked()
		return
	}
	// 多余的空桌收掉（只保留桌号最小者，其余广播 closed）
	sort.Slice(empties, func(i, j int) bool { return empties[i].tableNo < empties[j].tableNo })
	for _, r := range empties[1:] {
		h.destroyEmptyLobbyRoomLocked(r)
	}
}

// destroyEmptyLobbyRoomLocked 销毁多余空桌。调用方必须持有 lobbyMu，且目标必须为空桌。
func (h *Hub) destroyEmptyLobbyRoomLocked(r *Room) {
	r.mu.Lock()
	if r.closed || r.statusLocked() != tableEmpty {
		r.mu.Unlock()
		return
	}
	r.closed = true
	r.disarmRetentionLocked()
	r.mu.Unlock()

	h.mu.Lock()
	delete(h.rooms, r.code)
	delete(h.lobbyTables, r.tableNo)
	h.mu.Unlock()

	h.broadcastTableUpdateLocked(TableInfo{No: r.tableNo, Status: tableClosed})
	log.Printf("lobby table %d destroyed (surplus empty)", r.tableNo)
}

// allocCodeLocked 生成不冲突的 6 位数字房间号（允许前导零）。调用方必须持有 h.mu。
func (h *Hub) allocCodeLocked() string {
	for i := 0; i < 10; i++ {
		b := make([]byte, 6)
		if _, err := rand.Read(b); err != nil {
			// crypto/rand 失败属于极端环境异常，退化为时间戳并继续
			return fmt.Sprintf("%06d", time.Now().UnixNano()%1000000)
		}
		code := make([]byte, 6)
		for j := range b {
			code[j] = '0' + b[j]%10
		}
		s := string(code)
		if _, exists := h.rooms[s]; !exists {
			return s
		}
	}
	// 理论上 10 次碰撞概率可忽略；兜底用纳秒
	return fmt.Sprintf("%06d", time.Now().UnixNano()%1000000)
}

// randToken 生成 128bit 随机身份令牌（32 位十六进制）。
func randToken() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		// 兜底：时间戳填充（仍优于可猜测的短码）
		now := time.Now().UnixNano()
		for i := range b {
			b[i] = byte(now >> (uint(i) * 8))
		}
	}
	return hex.EncodeToString(b)
}
