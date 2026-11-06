package main

import "encoding/json"

// 协议消息类型，与 APP 端 BattleProtocol.kt 完全对齐。
// 服务器纯转发：除房间管理（create/join/leave）与心跳（ping）外，
// 对局消息原样转发给房间内的另一连接；服务器不存棋局、不校验走法、不跑引擎。
const (
	msgCreateRoom       = "create_room"
	msgJoinRoom         = "join_room"
	msgLeaveRoom        = "leave_room"
	msgRoomCreated      = "room_created"
	msgRoomJoined       = "room_joined"
	msgError            = "error"
	msgStartGame        = "start_game"
	msgMove             = "move"
	msgRequestSync      = "request_sync"
	msgSyncState        = "sync_state"
	msgResign           = "resign"
	msgPing             = "ping"
	msgPong             = "pong"
	msgOpponentOffline  = "opponent_offline"
	msgOpponentOnline   = "opponent_online"
	msgDrawOffer        = "draw_offer"
	msgDrawAccept       = "draw_accept"
	msgDrawDecline      = "draw_decline"
	msgNewGame          = "new_game"
	msgUndoRequest      = "undo_request"
	msgUndoAccept       = "undo_accept"
	msgUndoDecline      = "undo_decline"
	msgChatText         = "chat_text"  // 好友对战文字聊天（转发，服务器不留存）
	msgChatVoice        = "chat_voice" // 好友对战语音聊天（AMR base64，转发，服务器不留存）

	// ---- 大厅（棋桌）模式 ----
	msgEnterLobby  = "enter_lobby"  // 进入大厅：订阅桌状态广播，回 lobby_state 全量
	msgLeaveLobby  = "leave_lobby"  // 离开大厅：退订广播（入座/观战状态不受影响）
	msgCreateTable = "create_table" // 新建空桌：已有空桌时拒绝（errEmptyTableExists）
	msgSit         = "sit"          // 入座：可指定颜色，默认先坐红；坐满自动开局
	msgStand       = "stand"        // 离座：未开局释放座位；已开局等价 leave_room 解散棋桌
	msgWatch       = "watch"        // 观战：回 watch_ok（含走子缓存），后续实时收到对局消息
	msgUnwatch     = "unwatch"      // 结束观战
	msgNickUpdate  = "nick_update"  // 修改昵称：已入座/观战席位同步广播
	msgLobbyState  = "lobby_state"  // 大厅全量快照（enter_lobby 的应答）
	msgTableUpdate = "table_update" // 单桌增量广播（info.status=closed 表示该桌已销毁）
	msgSitOk       = "sit_ok"       // 入座成功（携带桌号/颜色/房号/token，供断线重连）
	msgWatchOk     = "watch_ok"     // 观战成功（携带双方昵称与走子缓存，供本地重放）
	msgWatchEnd    = "watch_end"    // 观战的棋桌已销毁
	msgOpponentLeft = "opponent_left" // 终局后对手离座：棋桌保留，收到方座位不变

	colorRed   = "red"
	colorBlack = "black"

	// 棋桌状态（TableInfo.status）
	tableEmpty   = "empty"   // 空桌（两席位均无主）——全局恰好一张，由服务器维护
	tableWaiting = "waiting" // 已有一人入座，等待对手
	tablePlaying = "playing" // 对局进行中
	tableClosed  = "closed"  // 已销毁（仅在 table_update 中出现，客户端应移除该卡片）
)

// 业务错误码（APP 端只做 toast 展示）
const (
	errBadMessage    = "bad_message"     // JSON 无法解析
	errNotJoined     = "not_in_room"     // 未建房/加入前发送对局消息
	errInvalidToken  = "invalid_token"   // token 缺失或错误（防猜号旁观）
	errRoomNotFound  = "room_not_found"  // 房间不存在或已超时关闭
	errRoomFull      = "room_full"       // 房间已有两人
	errRoomNotReady  = "room_not_ready"  // 房主不在线，无法加入
	errAlreadyInRoom = "already_in_room" // 同一连接重复建房
	errNotStarted    = "not_started"     // 对局未开始

	errEmptyTableExists = "empty_table_exists" // 已存在空桌，新建被拒
	errSeatTaken        = "seat_taken"         // 目标座位已被占用
	errTableNotFound    = "table_not_found"    // 棋桌不存在或已销毁
)

// 自定义 WebSocket 关闭码（4000-4099 为私有段）：
// 4001 房间已终态关闭（对方离开/房间超时），客户端收到后不得自动重连。
const closeRoomClosed = 4001

// Msg 是线上 JSON 信封（扁平结构）。字段与 APP 端一一对应，
// 转发时由服务器重新编码并剥掉 token，避免一方的身份令牌泄露给另一方。
type Msg struct {
	Type     string   `json:"type"`
	Room     string   `json:"room,omitempty"`
	Token    string   `json:"token,omitempty"`
	Color    string   `json:"color,omitempty"`
	UCCI     string   `json:"ucci,omitempty"`
	Seq      int      `json:"seq,omitempty"`
	FEN      string   `json:"fen,omitempty"`
	Moves    []string `json:"moves,omitempty"`
	Turn     string   `json:"turn,omitempty"`
	TS       int64    `json:"ts,omitempty"`
	TTL      int      `json:"ttl,omitempty"`      // opponent_offline：房间保留时长（秒），APP 据此倒计时
	Code     string   `json:"code,omitempty"`
	Msg      string   `json:"msg,omitempty"`       // leave_room：关闭原因（如保留超时）
	WantRoom string   `json:"want_room,omitempty"` // 房主建房时请求的期望房间号（可选，不保证）
	Text     string   `json:"text,omitempty"`      // chat_text：文字内容（APP 端限 40 字）
	Data     string   `json:"data,omitempty"`      // chat_voice：AMR-NB base64（最长 20 秒）
	Dur      int      `json:"dur,omitempty"`       // chat_voice：语音时长（毫秒）
	Gen      int      `json:"gen,omitempty"`       // start_game：服务端权威对局代数（新一局递增，客户端据此判断是否为换局）

	// ---- 大厅（棋桌）模式字段 ----
	Nick      string      `json:"nick,omitempty"`      // enter_lobby / nick_update：本人昵称
	Table     int         `json:"table,omitempty"`     // sit/stand/watch/unwatch/sit_ok/watch_ok/watch_end：桌号
	AfterGame bool        `json:"after_game,omitempty"` // stand：对局已终局后的离座（保留棋桌，不通知解散）
	Red       string      `json:"red,omitempty"`       // start_game/watch_ok/table_update：红方昵称
	Black  string      `json:"black,omitempty"`  // start_game/watch_ok/table_update：黑方昵称
	Tables []TableInfo `json:"tables,omitempty"` // lobby_state：全量桌列表
	Info   *TableInfo  `json:"info,omitempty"`   // table_update：单桌增量（status=closed 表示已销毁）
}

// TableInfo 是大厅卡片展示所需的单桌快照。
type TableInfo struct {
	No       int    `json:"no"`
	Status   string `json:"status"` // empty / waiting / playing / closed
	Red      string `json:"red,omitempty"`
	Black    string `json:"black,omitempty"`
	Watchers int    `json:"watchers"` // 观战人数
}

func mustJSON(m Msg) []byte {
	b, err := json.Marshal(m)
	if err != nil {
		// 所有字段均为基础类型，正常情况下不可能失败
		panic(err)
	}
	return b
}

func errorMsg(code, text string) Msg {
	return Msg{Type: msgError, Code: code, Msg: text}
}
