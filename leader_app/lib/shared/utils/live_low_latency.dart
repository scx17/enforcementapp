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
  // mpv 官方低延迟预设：已包含 audio-buffer=0 / cache-pause=no /
  // demuxer-lavf-o-add=fflags=+nobuffer / demuxer-lavf-analyzeduration=0 等组合，
  // 是把直播延迟压到亚秒级的核心。
  await platform.setProperty('profile', 'low-latency');
  // 进一步关掉前向预读，始终贴最新帧。
  await platform.setProperty('demuxer-readahead-secs', '0');
  // 注意：不要设 cache=no —— 对 HTTP-FLV 网络流会触发
  // "you can't force it with --force-seekable=yes" 并导致播放失败
  // （网络流需要 stream cache 才能正常 demux）。low-latency profile
  // 已把 cache 调到足够小，无需也不应强制关闭。
}
