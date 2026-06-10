import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';
import 'package:hdc_mobile/features/conference/data/conference_repository.dart';
import 'package:hdc_mobile/features/conference/presentation/conference_room_page.dart';
import 'package:hdc_mobile/features/devices/application/device_provider.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';
import 'package:hdc_mobile/shared/utils/device_label.dart';

/// S14 集群对讲 — 成员选择。多选在线设备后发起喊话会议。
class MemberSelectPage extends ConsumerStatefulWidget {
  const MemberSelectPage({super.key});

  @override
  ConsumerState<MemberSelectPage> createState() => _MemberSelectPageState();
}

class _MemberSelectPageState extends ConsumerState<MemberSelectPage> {
  final Set<String> _selected = {};
  bool _starting = false;

  Future<void> _start(List<DeviceModel> online) async {
    final members =
        online.where((d) => _selected.contains(d.deviceId)).toList();
    if (members.isEmpty) return;

    setState(() => _starting = true);
    try {
      final repo = await ref.read(conferenceRepositoryProvider.future);
      final name = '集群喊话 ${DateFormat('HH:mm').format(DateTime.now())}';
      final id = await repo.create(name: name, members: members);
      await repo.start(id);
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute<void>(
          builder: (_) => ConferenceRoomPage(
            conferenceId: id,
            name: name,
            members: members,
          ),
        ),
      );
    } on AppException catch (e) {
      if (!mounted) return;
      setState(() => _starting = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('发起喊话失败：${e.message}')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final online = ref.watch(deviceListProvider).valueOrNull
            ?.where((d) => d.isOnline)
            .toList() ??
        [];
    final allSelected =
        online.isNotEmpty && _selected.length == online.length;

    return Scaffold(
      backgroundColor: AppColors.scaffold,
      appBar: AppBar(
        backgroundColor: AppColors.scaffold,
        title: const Text('选择喊话对象'),
        actions: [
          TextButton(
            onPressed: online.isEmpty
                ? null
                : () => setState(() {
                      if (allSelected) {
                        _selected.clear();
                      } else {
                        _selected
                          ..clear()
                          ..addAll(online.map((d) => d.deviceId));
                      }
                    }),
            child: Text(allSelected ? '取消全选' : '全选在线'),
          ),
        ],
      ),
      body: online.isEmpty
          ? const Center(
              child: Text(
                '暂无在线设备',
                style: TextStyle(color: AppColors.textMuted, fontSize: 14),
              ),
            )
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      '在线设备 · ${online.length}',
                      style: const TextStyle(
                        color: AppColors.textSecondary,
                        fontSize: 12,
                      ),
                    ),
                  ),
                ),
                Expanded(
                  child: ListView.builder(
                    itemCount: online.length,
                    itemBuilder: (_, i) {
                      final d = online[i];
                      final checked = _selected.contains(d.deviceId);
                      return _SelectItem(
                        device: d,
                        checked: checked,
                        onTap: () => setState(() {
                          if (checked) {
                            _selected.remove(d.deviceId);
                          } else {
                            _selected.add(d.deviceId);
                          }
                        }),
                      );
                    },
                  ),
                ),
                _Footer(
                  count: _selected.length,
                  busy: _starting,
                  onStart: _selected.isEmpty ? null : () => _start(online),
                ),
              ],
            ),
    );
  }
}

class _SelectItem extends StatelessWidget {
  const _SelectItem({
    required this.device,
    required this.checked,
    required this.onTap,
  });

  final DeviceModel device;
  final bool checked;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: AppColors.divider)),
        ),
        child: Row(
          children: [
            Container(
              width: 22,
              height: 22,
              decoration: BoxDecoration(
                color: checked ? AppColors.primary : Colors.transparent,
                borderRadius: BorderRadius.circular(6),
                border: Border.all(
                  color: checked ? AppColors.primary : AppColors.textMuted,
                  width: 1.5,
                ),
              ),
              child: checked
                  ? const Icon(Icons.check, size: 15, color: Colors.white)
                  : null,
            ),
            const SizedBox(width: 12),
            const Icon(Icons.person_outline,
                color: AppColors.textSecondary, size: 20),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                formatDeviceLabel(device),
                style: const TextStyle(
                  color: AppColors.textPrimary,
                  fontSize: 15,
                ),
              ),
            ),
            Container(
              width: 8,
              height: 8,
              decoration: const BoxDecoration(
                color: AppColors.online,
                shape: BoxShape.circle,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Footer extends StatelessWidget {
  const _Footer({
    required this.count,
    required this.busy,
    required this.onStart,
  });

  final int count;
  final bool busy;
  final VoidCallback? onStart;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: AppColors.card,
      padding: EdgeInsets.fromLTRB(
        16,
        14,
        16,
        14 + MediaQuery.of(context).padding.bottom,
      ),
      child: SizedBox(
        width: double.infinity,
        child: FilledButton.icon(
          onPressed: busy ? null : onStart,
          icon: busy
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Colors.white,
                  ),
                )
              : const Icon(Icons.campaign),
          label: Text(busy ? '正在发起…' : '开始喊话（已选 $count 人）'),
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.primary,
            padding: const EdgeInsets.symmetric(vertical: 14),
          ),
        ),
      ),
    );
  }
}
