import 'package:hdc_mobile/shared/models/device_model.dart';

/// 标准展示名：name → customCodeDisplay → customCode → deviceId
String formatDeviceLabel(DeviceModel d) {
  if (d.name.isNotEmpty) return d.name;
  if (d.customCodeDisplay?.isNotEmpty == true) return d.customCodeDisplay!;
  if (d.customCode?.isNotEmpty == true) return d.customCode!;
  return d.deviceId;
}

/// 紧凑展示（地图气泡等）：同上，但 fallback 时只取末 4 位 deviceId
String formatDeviceShort(DeviceModel d) {
  if (d.name.isNotEmpty) return d.name;
  if (d.customCodeDisplay?.isNotEmpty == true) return d.customCodeDisplay!;
  if (d.customCode?.isNotEmpty == true) return d.customCode!;
  final id = d.deviceId;
  return id.length > 4 ? id.substring(id.length - 4) : id;
}
