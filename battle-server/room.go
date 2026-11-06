package main

import (
	"log"
	"sync"
	"time"
)

// pendingMsg 为对手离线期间暂存的对局消息。
type pendingMsg struct {
	typ  string // 原始消息类型，供 new_game 作废旧局协商用
	data []byte // 已剥 token、JSON 编码的完整帧
}

// maxPendingPerSeat 单席位待投递队列上限（roomTTL 内的正常对局远达不到，
// 纯兜底防止异常场景内存无限增长，超限丢最旧）。
const maxPendingPerSeat = 200

// Room 是一个两人房间。host=建房者执红，guest=加入者执黑。
// 任一方断线后对应指针置 nil（token 仍保留），房间保留 roomTTL 供重连。
type Room struct {
	code string
	hub  *Hub

	mu sync.Mutex

	host  *Client
	guest *Client

	hostToken  string
	guestToken string

	// 待投递队列：对手离线期间暂存发给该席位的对局消息（走子/求和/悔棋/认输/新局），
	// 该席位置新上线（token 重连）时按序补投。房间销毁时随之废弃。
	pendingHost  []pendingMsg
	pendingGuest []pendingMsg

	started   bool
	closed    bool
	retention *time.Timer

	// 对局代数：每开一局（首次开局/再来一局/赛后新对手入座）自增，
	// 随 start_game 下发。客户端据此区分"重连补发的同一局"与"离线期间错过的新局"。
	gameEpoch int

	createdAt time.Time

	// ---- 大厅（棋桌）模式字段（isLobby=false 时全部闲置）----
	isLobby   bool   // 大厅棋桌：走大厅生命周期（不断开连接、状态广播）
	tableNo   int    // 大厅桌号（最小未占用号，桌解散后回收复用）
	hostNick  string // 红方昵称（hostPlaysRed=false 时实为黑方昵称，见 seatColorLocked）
	guestNick string // 黑方昵称（hostPlaysRed=false 时实为红方昵称）

	// 先后手归属：host 席位执红？true=host 红/guest 黑（入座默认）。
	// "再来一局"由服务端仲裁达成后翻转——颜色唯一权威在服务端，
	// 客户端永不自行换色，双红/双黑结构性不可能。
	hostPlaysRed bool

	// 再来一局意愿（双方各表达一次，达成后统一清零）：
	// 按席位记录（与连接/颜色无关），服务端重连恢复也不受影响。
	rematchWantHost  bool
	rematchWantGuest bool

	// 旁观者集合：实时收到对局消息（走子/悔棋/求和/认输/新局），不收聊天。
	watchers map[*Client]bool

	// 走子缓存：供中途观战者本地重放。new_game 清空，undo 按"撤两着"回退。
	moveCache []string
	// 悔棋跟踪：undo_request 发起方颜色。undo_accept 或互为悔棋（双方各发一次
	// undo_request，无 undo_accept）时缓存撤两着；undo_decline/新走子/新局时清除。
	pendingUndoColor string
}

// retentionTTL 返回当前阶段应使用的保留时长：
//   - 对局已开始：使用 hub.roomTTL（默认 10 分钟，供断线重连恢复棋局）
//   - 建房等待中：使用较短的 preGameTTL（默认 30 秒），
//     房主切后台/网络抖动导致的短暂断线可重连恢复，超过则销毁房间。
func (r *Room) retentionTTL() time.Duration {
	if r.started {
		return r.hub.roomTTL
	}
	return r.hub.preGameTTL
}

// armRetention 在房间存在空槽位时启动保留计时；期间任一方重连即撤销（hub.join）。
// 到期由 hub.expireRetention 处理：仍有在线端则通知后关闭，否则静默回收。
func (r *Room) armRetention() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed || (r.host != nil && r.guest != nil) {
		return
	}
	if r.isLobby {
		// 大厅桌：只要有任一席主在线（坐着等对手），永不因计时收桌——
		// 玩家在线等待不能被 60 秒倒计时踢出。retention 只服务两种情形：
		//   1) 双方都离线但有座位 token：保留 preGameTTL 供断线重连；
		//   2) 全空桌：常驻资源，同样不计时（由 ensureEmptyTables 维护）。
		if r.host != nil || r.guest != nil {
			return
		}
		if r.hostToken == "" && r.guestToken == "" {
			return
		}
	}
	r.disarmRetentionLocked()
	r.retention = time.AfterFunc(r.retentionTTL(), func() {
		r.hub.expireRetention(r)
	})
	// 诊断日志：下次再出现"对局中被强制解散"，可据此判断是否误武装计时器
	log.Printf("room %s retention armed: ttl=%s started=%v table=%d hostOnline=%v guestOnline=%v",
		r.code, r.retentionTTL(), r.started, r.tableNo, r.host != nil, r.guest != nil)
}

// disarmRetentionLocked 调用方必须持有 r.mu。
func (r *Room) disarmRetentionLocked() {
	if r.retention != nil {
		r.retention.Stop()
		r.retention = nil
	}
}

// startedLocked 安全读取对局是否已开始。
func (r *Room) isStarted() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.started
}

// seatColorLocked 返回指定席位的执子颜色。hostPlaysRed=true（入座默认）
// host 红/guest 黑；"再来一局"仲裁达成后翻转。调用方必须持有 r.mu。
func (r *Room) seatColorLocked(isHost bool) string {
	if isHost == r.hostPlaysRed {
		return colorRed
	}
	return colorBlack
}

// nickForSeatLocked 返回指定席位当前执色对应的昵称槽。昵称按执色存放
// （hostNick=红方/guestNick=黑方），换色后不能按席位直接读。调用方必须持有 r.mu。
func (r *Room) nickForSeatLocked(isHost bool) string {
	if r.seatColorLocked(isHost) == colorRed {
		return r.hostNick
	}
	return r.guestNick
}

// startFrameLocked 生成发给指定执色席位的 start_game 帧：双方昵称、本人执色
// 与对局代数均取自服务端权威状态（重连补发也用它，客户端不会再拿到"无颜色"的开局）。
// color 为空时生成旁观者帧（旁观无执色概念）。调用方必须持有 r.mu。
func (r *Room) startFrameLocked(color string) []byte {
	return mustJSON(Msg{
		Type: msgStartGame, Room: r.code, Red: r.hostNick, Black: r.guestNick,
		Color: color, Gen: r.gameEpoch,
	})
}

// requestRematch 处理 new_game（服务端仲裁）：
//   - 记录本方意愿；对方尚未表达时把 new_game 转发给对方（在线直发/离线入
//     pending 补投），沿用客户端既有的"对方请求新一局"协商 UI；
//   - 双方均已表达：达成新局——交换先后手（hostPlaysRed 翻转，颜色唯一权威
//     在服务端，客户端永不自行换色）、清走子缓存与双方 pending（旧局消息全部
//     作废）、向双方分帧发 start_game（各带本人颜色）、向旁观者广播 start_game。
//
// 双方达成后颜色交换只发生这一次，与双方连接生死/重连无关（重连恢复按
// seatColorLocked 取色），双红/双黑结构性不可能。
func (r *Room) requestRematch(c *Client) {
	r.mu.Lock()
	if r.closed || !r.started {
		r.mu.Unlock()
		return
	}
	var oppColor string
	switch {
	case c == r.host:
		if r.rematchWantHost {
			r.mu.Unlock() // 重复点击忽略
			return
		}
		r.rematchWantHost = true
		oppColor = r.seatColorLocked(false)
	case c == r.guest:
		if r.rematchWantGuest {
			r.mu.Unlock() // 重复点击忽略
			return
		}
		r.rematchWantGuest = true
		oppColor = r.seatColorLocked(true)
	default:
		r.mu.Unlock() // 旁观者等非席位连接：忽略
		return
	}

	// 单方意愿：转发 new_game 给对方（沿用既有协商流程），旁观者不感知
	if !(r.rematchWantHost && r.rematchWantGuest) {
		m := Msg{Type: msgNewGame}
		m.Token = ""
		payload := mustJSON(m)
		var peer *Client
		if c == r.host {
			peer = r.guest
		} else {
			peer = r.host
		}
		r.mu.Unlock()
		if peer != nil {
			peer.enqueue(payload)
		} else {
			r.enqueuePending(oppColor, msgNewGame, payload)
		}
		return
	}

	// ---- 双方意愿达成：交换先后手并重开新局 ----
	r.hostPlaysRed = !r.hostPlaysRed
	r.hostNick, r.guestNick = r.guestNick, r.hostNick
	r.rematchWantHost, r.rematchWantGuest = false, false
	r.moveCache = nil
	r.pendingUndoColor = ""
	r.gameEpoch++
	// 旧局已终：双方 pending 全部作废（残留的走子/协商消息按旧色键控，恢复即错位）
	r.pendingHost = nil
	r.pendingGuest = nil

	// 翻转后各席位的执子色（hostColor/guestColor 即分发依据，勿再按
	// "host=红"直觉硬编码——翻转后 host 执黑、guest 执红）
	roomCode := r.code
	redNick := r.hostNick
	blackNick := r.guestNick
	host, guest := r.host, r.guest
	watchers := r.watcherListLocked()
	hostColor := r.seatColorLocked(true)
	guestColor := r.seatColorLocked(false)
	gen := r.gameEpoch
	makeStart := func(color string) []byte {
		return mustJSON(Msg{
			Type: msgStartGame, Room: roomCode, Red: redNick, Black: blackNick,
			Color: color, Gen: gen,
		})
	}
	watchFrame := mustJSON(Msg{Type: msgStartGame, Room: roomCode, Red: redNick, Black: blackNick, Gen: gen})

	// 分帧下发：每人一帧 start_game 且带本人（交换后）的执子色，旁观者不带色。
	// 在线席位在锁内直发，保证 start_game 先于随后的走子到达；离线席位
	// （连接已断但 token 保留）把自己的开局帧放到 pending 队首，重连时
	// 先补投新局再补投其后的走子，否则客户端会停在旧局棋盘上无法走棋。
	if host != nil {
		host.enqueue(makeStart(hostColor))
	} else {
		r.appendPendingLocked(hostColor, msgStartGame, makeStart(hostColor))
	}
	if guest != nil {
		guest.enqueue(makeStart(guestColor))
	} else {
		r.appendPendingLocked(guestColor, msgStartGame, makeStart(guestColor))
	}
	for _, w := range watchers {
		w.enqueue(watchFrame)
	}
	r.mu.Unlock()

	if host != nil {
		host.bind(r, hostColor, host.getToken())
	}
	if guest != nil {
		guest.bind(r, guestColor, guest.getToken())
	}
	log.Printf("room %s rematch started gen=%d: hostColor=%s guestColor=%s (hostPlaysRed flipped), hostOnline=%v guestOnline=%v",
		roomCode, gen, hostColor, guestColor, host != nil, guest != nil)
}

// peerOf 返回房间内的另一方；不在房间内返回 nil。
func (r *Room) peerOf(c *Client) *Client {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.host == c {
		return r.guest
	}
	if r.guest == c {
		return r.host
	}
	return nil
}

// onClientGone 处理连接断开：摘下本端、通知对手、启动保留计时。
// 被已替换的旧连接（重连幽灵连接）不触发任何处理。
func (r *Room) onClientGone(c *Client) {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return
	}
	var peer *Client
	switch {
	case r.host == c:
		r.host = nil
		peer = r.guest
	case r.guest == c:
		r.guest = nil
		peer = r.host
	default:
		// 已被重连替换的旧连接：什么都不做
		r.mu.Unlock()
		return
	}

	// 注意：建房等待阶段（started=false）的意外断线不立即销毁房间，
	// 而是走下方 armRetention（preGameTTL=30s）让房主有机会重连恢复；
	// 主动 leave_room 由 handleMessage 直接调 teardown 立即销毁。
	r.disarmRetentionLocked()
	log.Printf("room %s seat gone (started=%v, peerOnline=%v)", r.code, r.started, peer != nil)
	r.mu.Unlock()

	if peer != nil {
		peer.enqueue(mustJSON(Msg{
			Type: msgOpponentOffline, Room: r.code,
			TTL: int(r.hub.roomTTL / time.Second), // APP 据此显示重连倒计时
		}))
	}
	// 无论对手是否在线都启动保留计时：对手在线但一直不重连时，
	// 到期通知在线方并关闭房间（此前仅双方都离线才计时，房间会被无限占用）
	r.armRetention()
}

// opponentColorOf 返回另一方的执色（队列按接收方席位索引）。
func opponentColorOf(color string) string {
	if color == colorBlack {
		return colorRed
	}
	return colorBlack
}

// pendingSlot 返回指定执色席位的待投递队列指针（调用方必须持有 r.mu）。
func (r *Room) pendingSlot(color string) *[]pendingMsg {
	if color == colorBlack {
		return &r.pendingGuest
	}
	return &r.pendingHost
}

// enqueuePending 对手离线时暂存发给其席位的对局消息，待其重连后按序补投。
// new_game 会作废该队列中尚未投递的求和/悔棋请求：旧局协商随新局失效，
// 补投过期请求会诱导双方在已重置的棋盘上误操作（FIFO 保序已含因果顺序）。
func (r *Room) enqueuePending(color, typ string, payload []byte) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.appendPendingLocked(color, typ, payload)
}

// appendPendingLocked 与 enqueuePending 相同，但调用方必须持有 r.mu。
func (r *Room) appendPendingLocked(color, typ string, payload []byte) {
	slot := r.pendingSlot(color)
	if typ == msgNewGame {
		kept := (*slot)[:0]
		for _, p := range *slot {
			if p.typ == msgDrawOffer || p.typ == msgUndoRequest {
				continue
			}
			kept = append(kept, p)
		}
		*slot = kept
	}
	if len(*slot) >= maxPendingPerSeat {
		*slot = (*slot)[1:]
	}
	*slot = append(*slot, pendingMsg{typ: typ, data: payload})
}

// drainPending 取走并清空指定席位的待投递队列，返回按到达序排列的补投列表。
func (r *Room) drainPending(color string) []pendingMsg {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.drainPendingLocked(color)
}

// drainPendingLocked 与 drainPending 相同，但调用方必须持有 r.mu。
func (r *Room) drainPendingLocked(color string) []pendingMsg {
	slot := r.pendingSlot(color)
	out := *slot
	*slot = nil
	return out
}

// pendingHasStart 判断待投递队列中是否已含新局 start_game（离线期间达成
// "再来一局"时该帧排在队首）。含则重连时无需再补发，否则会重复触发换局。
func pendingHasStart(pending []pendingMsg) bool {
	for _, p := range pending {
		if p.typ == msgStartGame {
			return true
		}
	}
	return false
}

// ---- 大厅（棋桌）模式辅助 ----

// statusLocked 计算棋桌状态。调用方必须持有 r.mu。
// 已开局优先判定：对局中一方断线（席位指针为 nil）仍是 playing。
func (r *Room) statusLocked() string {
	if r.started {
		return tablePlaying
	}
	if r.hostToken == "" && r.guestToken == "" {
		return tableEmpty
	}
	return tableWaiting
}

// tableInfo 生成该桌的大厅卡片快照。
func (r *Room) tableInfo() TableInfo {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.tableInfoLocked()
}

// tableInfoLocked 调用方必须持有 r.mu。
func (r *Room) tableInfoLocked() TableInfo {
	return TableInfo{
		No:       r.tableNo,
		Status:   r.statusLocked(),
		Red:      r.hostNick,
		Black:    r.guestNick,
		Watchers: len(r.watchers),
	}
}

// isClosed 安全读取房间是否已终态关闭。
func (r *Room) isClosed() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.closed
}

// addWatcher 将连接加入旁观席。调用方必须持有 r.mu。
func (r *Room) addWatcherLocked(c *Client) {
	if r.watchers == nil {
		r.watchers = make(map[*Client]bool)
	}
	r.watchers[c] = true
}

// removeWatcher 将连接移出旁观席，返回是否在席。
func (r *Room) removeWatcher(c *Client) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.watchers[c]; !ok {
		return false
	}
	delete(r.watchers, c)
	return true
}

// watcherListLocked 返回旁观者快照。调用方必须持有 r.mu。
func (r *Room) watcherListLocked() []*Client {
	out := make([]*Client, 0, len(r.watchers))
	for w := range r.watchers {
		out = append(out, w)
	}
	return out
}

// broadcastWatchersLocked 向全部旁观者投递已编码帧。调用方必须持有 r.mu。
func (r *Room) broadcastWatchersLocked(payload []byte) {
	for w := range r.watchers {
		w.enqueue(payload)
	}
}

// movesSnapshotLocked 返回走子缓存副本。调用方必须持有 r.mu。
func (r *Room) movesSnapshotLocked() []string {
	out := make([]string, len(r.moveCache))
	copy(out, r.moveCache)
	return out
}

// trackGameMessage 维护观战走子缓存与悔棋跟踪，并向旁观者广播。
// 仅处理对局消息（走子/悔棋/求和/认输/新局）；聊天与对账不经过此函数。
// 返回给旁观者的帧与给对手的一致（已剥 token）。
func (r *Room) trackGameMessage(typ, ucci, fromColor string, payload []byte) {
	if !r.isLobby {
		return
	}
	r.mu.Lock()
	switch typ {
	case msgMove:
		r.moveCache = append(r.moveCache, ucci)
		r.pendingUndoColor = ""
	case msgUndoRequest:
		if r.pendingUndoColor != "" && r.pendingUndoColor != fromColor {
			// 互为悔棋：双方各发一次 undo_request 即视为达成（客户端本地回退，
			// 不再互发 undo_accept）——缓存同步撤两着
			r.popMoveCacheLocked(2)
			r.pendingUndoColor = ""
		} else {
			r.pendingUndoColor = fromColor
		}
	case msgUndoAccept:
		r.popMoveCacheLocked(2)
		r.pendingUndoColor = ""
	case msgUndoDecline:
		r.pendingUndoColor = ""
	}
	// 注：new_game 不再走 trackGameMessage——服务端仲裁见 Room.requestRematch
	// （单方意愿不广播旁观者；达成时走子缓存清空、旁观者改收 start_game）。
	r.broadcastWatchersLocked(payload)
	r.mu.Unlock()
}

// popMoveCacheLocked 从走子缓存尾部弹出 n 着（不足则清空）。调用方必须持有 r.mu。
func (r *Room) popMoveCacheLocked(n int) {
	if len(r.moveCache) <= n {
		r.moveCache = nil
		return
	}
	r.moveCache = r.moveCache[:len(r.moveCache)-n]
}
