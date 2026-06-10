import 'dart:math';

import 'package:latlong2/latlong.dart';

/// WGS-84 → GCJ-02 坐标转换。
///
/// 后端/DB 存储 WGS-84（原始 GPS），高德瓦片是 GCJ-02（火星坐标，中国区加偏）。
/// 显示到高德底图前必须转换，否则位置偏移数百米。
/// 与平台前端 utils/map.js 的 coordtransform 行为一致（eviltransform 算法）。
class Gcj02 {
  Gcj02._();

  static const double _a = 6378245.0; // 克拉索夫斯基椭球长半轴
  static const double _ee = 0.00669342162296594323; // 偏心率平方

  /// 是否在中国境外（境外不偏移）。
  static bool _outOfChina(double lng, double lat) {
    return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
  }

  static double _transformLat(double x, double y) {
    var ret = -100.0 +
        2.0 * x +
        3.0 * y +
        0.2 * y * y +
        0.1 * x * y +
        0.2 * sqrt(x.abs());
    ret += (20.0 * sin(6.0 * x * pi) + 20.0 * sin(2.0 * x * pi)) * 2.0 / 3.0;
    ret += (20.0 * sin(y * pi) + 40.0 * sin(y / 3.0 * pi)) * 2.0 / 3.0;
    ret += (160.0 * sin(y / 12.0 * pi) + 320 * sin(y * pi / 30.0)) * 2.0 / 3.0;
    return ret;
  }

  static double _transformLng(double x, double y) {
    var ret =
        300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(x.abs());
    ret += (20.0 * sin(6.0 * x * pi) + 20.0 * sin(2.0 * x * pi)) * 2.0 / 3.0;
    ret += (20.0 * sin(x * pi) + 40.0 * sin(x / 3.0 * pi)) * 2.0 / 3.0;
    ret += (150.0 * sin(x / 12.0 * pi) + 300.0 * sin(x / 30.0 * pi)) *
        2.0 /
        3.0;
    return ret;
  }

  /// WGS-84 (lng, lat) → GCJ-02 LatLng（flutter_map 用 LatLng(lat, lng)）。
  static LatLng wgs84ToLatLng(double lng, double lat) {
    if (_outOfChina(lng, lat)) return LatLng(lat, lng);
    var dLat = _transformLat(lng - 105.0, lat - 35.0);
    var dLng = _transformLng(lng - 105.0, lat - 35.0);
    final radLat = lat / 180.0 * pi;
    var magic = sin(radLat);
    magic = 1 - _ee * magic * magic;
    final sqrtMagic = sqrt(magic);
    dLat = (dLat * 180.0) / ((_a * (1 - _ee)) / (magic * sqrtMagic) * pi);
    dLng = (dLng * 180.0) / (_a / sqrtMagic * cos(radLat) * pi);
    return LatLng(lat + dLat, lng + dLng);
  }
}
