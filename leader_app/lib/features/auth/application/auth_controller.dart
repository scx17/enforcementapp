import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';
import 'package:hdc_mobile/features/auth/data/auth_repository.dart';

class AuthState {
  const AuthState({
    this.isLoggedIn = false,
    this.isLoading = false,
    this.userName,
    this.displayName,
    this.orgName,
    this.error,
  });

  final bool isLoggedIn;
  final bool isLoading;
  final String? userName;
  final String? displayName;
  final String? orgName;
  final String? error;

  AuthState copyWith({
    bool? isLoggedIn,
    bool? isLoading,
    String? userName,
    String? displayName,
    String? orgName,
    String? error,
    bool clearError = false,
  }) {
    return AuthState(
      isLoggedIn: isLoggedIn ?? this.isLoggedIn,
      isLoading: isLoading ?? this.isLoading,
      userName: userName ?? this.userName,
      displayName: displayName ?? this.displayName,
      orgName: orgName ?? this.orgName,
      error: clearError ? null : error ?? this.error,
    );
  }
}

final authControllerProvider =
    AsyncNotifierProvider<AuthController, AuthState>(AuthController.new);

class AuthController extends AsyncNotifier<AuthState> {
  @override
  Future<AuthState> build() async {
    // 尝试恢复登录状态（持久化的 Session Cookie 仍有效时）
    final repo = await ref.watch(authRepositoryProvider.future);
    try {
      final user = await repo.getCurrentUser();
      if (user != null) {
        return AuthState(
          isLoggedIn: true,
          userName: user['userName']?.toString(),
          displayName: user['userName']?.toString() ??
              user['loginName']?.toString(),
          orgName: user['orgName']?.toString(),
        );
      }
    } on AppException {
      // 未登录或会话失效，返回默认状态
    }
    return const AuthState();
  }

  /// 登录。captcha 必填（后端强制校验图形验证码）。
  /// 返回 true=成功，false=失败（错误信息已写入 state.error）。
  Future<bool> login(
    String username,
    String password,
    String captcha,
  ) async {
    final current = state.valueOrNull ?? const AuthState();
    state = AsyncData(current.copyWith(isLoading: true, clearError: true));

    try {
      final repo = await ref.read(authRepositoryProvider.future);
      final result = await repo.login(username, password, captcha);
      if (!result.ok) {
        state = AsyncData(
          current.copyWith(isLoading: false, error: result.message),
        );
        return false;
      }
      final data = result.data ?? const {};
      state = AsyncData(
        current.copyWith(
          isLoggedIn: true,
          isLoading: false,
          userName: username,
          displayName: username,
          orgName: data['orgName']?.toString(),
          clearError: true,
        ),
      );
      return true;
    } on AppException catch (e) {
      state = AsyncData(
        current.copyWith(isLoading: false, error: e.message),
      );
      return false;
    }
  }

  Future<void> logout() async {
    final current = state.valueOrNull ?? const AuthState();
    state = AsyncData(current.copyWith(isLoading: true));

    try {
      final repo = await ref.read(authRepositoryProvider.future);
      await repo.logout();
    } on AppException {
      // 登出失败也清除本地状态
    } finally {
      state = const AsyncData(AuthState());
    }
  }
}
