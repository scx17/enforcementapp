class DeviceModel {
  const DeviceModel({
    required this.deviceId,
    required this.name,
    required this.status,
    this.customCode,
    this.customCodeDisplay,
    this.channelId,
    this.ip,
    this.manufacturer,
  });

  final String deviceId;
  final String name;
  final String? customCode;
  final String? customCodeDisplay;

  /// 后端 gb_device.Status 约定为 'ON' / 'OFF'
  final String status;
  final String? channelId;
  final String? ip;
  final String? manufacturer;

  /// 后端用 'ON'/'OFF'，兼容 'online'/'1'/'true' 等写法
  bool get isOnline {
    final s = status.toUpperCase();
    return s == 'ON' || s == 'ONLINE' || s == '1' || s == 'TRUE';
  }

  factory DeviceModel.fromJson(Map<String, dynamic> json) {
    return DeviceModel(
      deviceId: json['deviceId']?.toString() ?? json['id']?.toString() ?? '',
      name: json['name']?.toString() ??
          json['deviceName']?.toString() ??
          '',
      customCode: json['customCode']?.toString(),
      customCodeDisplay: json['customCodeDisplay']?.toString(),
      status: json['status']?.toString() ?? 'OFF',
      channelId: json['channelId']?.toString() ??
          json['mainChannelId']?.toString(),
      ip: json['ip']?.toString() ?? json['host']?.toString(),
      manufacturer: json['manufacturer']?.toString() ??
          json['deviceModel']?.toString(),
    );
  }

  Map<String, dynamic> toJson() => {
        'deviceId': deviceId,
        'name': name,
        'customCode': customCode,
        'customCodeDisplay': customCodeDisplay,
        'status': status,
        'channelId': channelId,
        'ip': ip,
        'manufacturer': manufacturer,
      };

  DeviceModel copyWith({
    String? deviceId,
    String? name,
    String? customCode,
    String? customCodeDisplay,
    String? status,
    String? channelId,
    String? ip,
    String? manufacturer,
  }) {
    return DeviceModel(
      deviceId: deviceId ?? this.deviceId,
      name: name ?? this.name,
      customCode: customCode ?? this.customCode,
      customCodeDisplay: customCodeDisplay ?? this.customCodeDisplay,
      status: status ?? this.status,
      channelId: channelId ?? this.channelId,
      ip: ip ?? this.ip,
      manufacturer: manufacturer ?? this.manufacturer,
    );
  }
}
