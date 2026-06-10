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
}
