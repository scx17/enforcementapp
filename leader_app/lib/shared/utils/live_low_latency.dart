import 'package:media_kit/media_kit.dart';

/// 实时直播（HTTP-FLV）低延迟调优。
///
/// media_kit 底层 libmpv 默认为流畅播放做较大缓冲与预读，实测端到端延迟约 3s，
/// 而平台 Web(flv.js) 贴边追帧 <1s。这里注入 mpv 低延迟参数把差距抹平。
///
/// 仅用于实时预览 / 对讲听音频，**回放(点播录像)不要调用** —— 回放需要稳定缓冲。
/// 必须在该 [Player] 首次 [Player.open] 之前调用。
Future<void> applyLiveLowLatency(Player player) async {
  final platform = player.platform;
  if (platform is! NativePlayer) return;
  // mpv 官方低延迟预设（audio-buffer=0 / cache-pause=no / nobuffer 等组合）
  await platform.setProperty('profile', 'low-latency');
  // 关闭前向缓存与预读，始终贴最新帧
  await platform.setProperty('cache', 'no');
  await platform.setProperty('demuxer-readahead-secs', '0');
  // ffmpeg 解复用不缓冲
  await platform.setProperty('demuxer-lavf-o', 'fflags=+nobuffer');
  // 最快起播：极小 probesize + 不做时长分析
  await platform.setProperty('demuxer-lavf-probesize', '32768');
  await platform.setProperty('demuxer-lavf-analyzeduration', '0');
  // 直播宁可丢帧也不为了缓存而暂停
  await platform.setProperty('cache-pause', 'no');
}
