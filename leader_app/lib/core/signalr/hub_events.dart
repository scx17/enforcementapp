abstract final class HubEvents {
  /// 新告警推送
  static const String newAlarm = 'NewAlarm';

  /// 设备上线
  static const String deviceOnline = 'DeviceOnline';

  /// 设备离线
  static const String deviceOffline = 'DeviceOffline';

  /// 流推送状态变化
  static const String streamStateChanged = 'StreamStateChanged';

  /// 对讲结束（设备侧挂断或平台 stop 后广播）
  static const String talkEnded = 'TalkEnded';

  /// 1对1 对讲下行音频（设备→指挥，裸 base64 PCM16 8kHz）
  static const String talkAudio = 'TalkAudio';

  /// 1对1 对讲结束（对端挂断，JSON 字符串 {talkId}）
  static const String p2pTalkEnded = 'P2pTalkEnded';

  // ── 集群对讲半双工发言权 / 音频 ──
  /// 发言权授予（仅请求者收到，载荷为 channelId 裸字符串）
  static const String floorGranted = 'FloorGranted';

  /// 发言权被拒（载荷 JSON 字符串 {channelId, holder}）
  static const String floorDenied = 'FloorDenied';

  /// 说话人变化（载荷 JSON 字符串 {channelId, deviceId, speaking}）
  static const String speakerChanged = 'SpeakerChanged';

  /// 会议组音频帧（载荷 Base64 PCM16 8kHz 裸字符串）
  static const String conferenceAudio = 'ConferenceAudio';

  /// 会议事件（载荷 JSON 字符串 {type, conferenceId, ...}，type=ended/member_joined/...）
  static const String conferenceEvent = 'ConferenceEvent';
}

abstract final class HubMethods {
  /// 加入设备组
  static const String joinGroup = 'JoinGroup';

  /// 离开设备组
  static const String leaveGroup = 'LeaveGroup';

  /// 加入对讲音频组
  static const String joinTalkGroup = 'JoinTalkGroup';

  /// 上行麦克风音频到设备（Base64 PCM16 8kHz 单声道）
  static const String sendAudioToDevice = 'SendAudioToDevice';

  /// 加入集群会议音频组
  static const String joinConferenceGroup = 'JoinConferenceGroup';

  /// 离开集群会议音频组
  static const String leaveConferenceGroup = 'LeaveConferenceGroup';

  /// 上行麦克风音频到会议组（喊话）
  static const String sendConferenceAudio = 'SendConferenceAudio';

  /// 加入设备通知组（同时向后端刷新在线时间，供组长硬退出兜底巡检判断）
  static const String joinDeviceNotificationGroup = 'JoinDeviceNotificationGroup';

  /// 请求发言权（半双工，args: channelId, deviceId）
  static const String requestFloor = 'RequestFloor';

  /// 释放发言权（args: channelId, deviceId）
  static const String releaseFloor = 'ReleaseFloor';
}
