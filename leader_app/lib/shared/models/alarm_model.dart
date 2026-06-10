class AlarmModel {
  const AlarmModel({
    required this.id,
    required this.deviceId,
    required this.type,
    required this.level,
    required this.occurredAt,
    this.deviceName,
    this.customCodeDisplay,
    this.message,
    this.isRead = false,
  });

  final String id;
  final String deviceId;
  final String? deviceName;
  final String? customCodeDisplay;

  /// 告警类型（服务端定义）
  final String type;

  /// 'high' | 'mid' | 'low'
  final String level;
  final String? message;
  final DateTime occurredAt;
  final bool isRead;

  bool get isHigh => level == 'high';
  bool get isMid => level == 'mid';
  bool get isLow => level == 'low';

  factory AlarmModel.fromJson(Map<String, dynamic> json) {
    return AlarmModel(
      id: json['id']?.toString() ?? '',
      deviceId: json['deviceId']?.toString() ?? '',
      deviceName: json['deviceName']?.toString(),
      customCodeDisplay: json['customCodeDisplay']?.toString(),
      type: json['type']?.toString() ?? '',
      level: json['level']?.toString() ?? 'low',
      message: json['message']?.toString(),
      occurredAt: _parseDate(json['occurredAt'] ?? json['createTime']),
      isRead: json['isRead'] == true,
    );
  }

  AlarmModel copyWith({bool? isRead}) {
    return AlarmModel(
      id: id,
      deviceId: deviceId,
      deviceName: deviceName,
      customCodeDisplay: customCodeDisplay,
      type: type,
      level: level,
      message: message,
      occurredAt: occurredAt,
      isRead: isRead ?? this.isRead,
    );
  }

  static DateTime _parseDate(dynamic value) {
    if (value == null) return DateTime.now();
    if (value is DateTime) return value;
    try {
      return DateTime.parse(value.toString());
    } on FormatException {
      return DateTime.now();
    }
  }
}
