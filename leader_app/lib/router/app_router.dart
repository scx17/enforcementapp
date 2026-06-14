import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';
import 'package:hdc_mobile/features/alarm/presentation/alarm_page.dart';
import 'package:hdc_mobile/features/auth/application/auth_controller.dart';
import 'package:hdc_mobile/features/auth/presentation/login_page.dart';
import 'package:hdc_mobile/features/conference/presentation/member_select_page.dart';
import 'package:hdc_mobile/features/devices/presentation/device_list_page.dart';
import 'package:hdc_mobile/features/monitor/presentation/monitor_page.dart';
import 'package:hdc_mobile/features/settings/presentation/settings_page.dart';
import 'package:hdc_mobile/features/alarm/application/alarm_provider.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final authState = ref.watch(authControllerProvider);

  return GoRouter(
    initialLocation: '/monitor',
    redirect: (context, state) {
      // 登录态加载中不重定向
      if (authState.isLoading) return null;

      final isLoggedIn = authState.valueOrNull?.isLoggedIn ?? false;
      final isGoingToLogin = state.matchedLocation == '/login';

      if (!isLoggedIn && !isGoingToLogin) return '/login';
      if (isLoggedIn && isGoingToLogin) return '/monitor';
      return null;
    },
    routes: [
      GoRoute(
        path: '/login',
        pageBuilder: (_, state) => const NoTransitionPage(child: LoginPage()),
      ),
      StatefulShellRoute.indexedStack(
        builder: (context, routerState, shell) {
          return _MainScaffold(shell: shell);
        },
        branches: [
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/monitor',
                pageBuilder: (ctx, st) =>
                    const NoTransitionPage(child: MonitorPage()),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/devices',
                pageBuilder: (ctx, st) =>
                    const NoTransitionPage(child: DeviceListPage()),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/intercom',
                pageBuilder: (ctx, st) =>
                    const NoTransitionPage(child: MemberSelectPage()),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/alarm',
                pageBuilder: (ctx, st) =>
                    const NoTransitionPage(child: AlarmPage()),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/settings',
                pageBuilder: (ctx, st) =>
                    const NoTransitionPage(child: SettingsPage()),
              ),
            ],
          ),
        ],
      ),
    ],
  );
});

class _MainScaffold extends ConsumerWidget {
  const _MainScaffold({required this.shell});

  final StatefulNavigationShell shell;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final unreadCount = ref.watch(unreadAlarmCountProvider);

    return Scaffold(
      body: shell,
      bottomNavigationBar: BottomNavigationBar(
        currentIndex: shell.currentIndex,
        onTap: (i) => shell.goBranch(
          i,
          initialLocation: i == shell.currentIndex,
        ),
        items: [
          const BottomNavigationBarItem(
            icon: Icon(Icons.videocam_outlined),
            activeIcon: Icon(Icons.videocam),
            label: '监控',
          ),
          const BottomNavigationBarItem(
            icon: Icon(Icons.devices_outlined),
            activeIcon: Icon(Icons.devices),
            label: '设备',
          ),
          const BottomNavigationBarItem(
            icon: Icon(Icons.campaign_outlined),
            activeIcon: Icon(Icons.campaign),
            label: '集群对讲',
          ),
          BottomNavigationBarItem(
            icon: _AlarmNavIcon(unread: unreadCount),
            activeIcon: _AlarmNavIcon(unread: unreadCount, active: true),
            label: '告警',
          ),
          const BottomNavigationBarItem(
            icon: Icon(Icons.person_outline),
            activeIcon: Icon(Icons.person),
            label: '我的',
          ),
        ],
      ),
    );
  }
}

class _AlarmNavIcon extends StatelessWidget {
  const _AlarmNavIcon({required this.unread, this.active = false});

  final int unread;
  final bool active;

  @override
  Widget build(BuildContext context) {
    return Stack(
      clipBehavior: Clip.none,
      children: [
        Icon(active ? Icons.notifications : Icons.notifications_outlined),
        if (unread > 0)
          Positioned(
            right: -6,
            top: -4,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
              decoration: BoxDecoration(
                color: AppColors.alarm,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                unread > 99 ? '99+' : '$unread',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 9,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ),
      ],
    );
  }
}
