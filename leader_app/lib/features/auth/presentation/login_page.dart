import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:hdc_mobile/core/config/app_config.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';
import 'package:hdc_mobile/features/auth/application/auth_controller.dart';
import 'package:hdc_mobile/features/auth/data/auth_repository.dart';

class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final _formKey = GlobalKey<FormState>();
  final _usernameCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  final _captchaCtrl = TextEditingController();
  bool _obscurePassword = true;
  bool _rememberLogin = false;

  Uint8List? _captchaImage;
  bool _captchaLoading = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _refreshCaptcha());
  }

  @override
  void dispose() {
    _usernameCtrl.dispose();
    _passwordCtrl.dispose();
    _captchaCtrl.dispose();
    super.dispose();
  }

  /// 拉取验证码图片。CookieJar 会自动保存 CaptchaToken Cookie。
  Future<void> _refreshCaptcha() async {
    setState(() => _captchaLoading = true);
    try {
      final repo = await ref.read(authRepositoryProvider.future);
      final bytes = await repo.fetchCaptcha();
      if (!mounted) return;
      setState(() {
        _captchaImage = bytes;
        _captchaLoading = false;
      });
    } on AppException {
      if (!mounted) return;
      setState(() => _captchaLoading = false);
    }
  }

  Future<void> _handleLogin() async {
    if (!_formKey.currentState!.validate()) return;
    final ok = await ref.read(authControllerProvider.notifier).login(
          _usernameCtrl.text.trim(),
          _passwordCtrl.text,
          _captchaCtrl.text.trim(),
        );
    if (ok) {
      if (mounted) context.go('/monitor');
    } else {
      // 登录失败（含验证码错误）：刷新验证码并清空输入
      _captchaCtrl.clear();
      await _refreshCaptcha();
    }
  }

  @override
  Widget build(BuildContext context) {
    final authState = ref.watch(authControllerProvider);
    final state = authState.valueOrNull ?? const AuthState();

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 32),
            child: Form(
              key: _formKey,
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const SizedBox(height: 48),
                  // Logo
                  _buildLogo(),
                  const SizedBox(height: 20),
                  // App 名称
                  const Text(
                    '执法监控',
                    style: TextStyle(
                      color: AppColors.textPrimary,
                      fontSize: 26,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 2,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    AppConfig.instance.orgName,
                    style: const TextStyle(
                      color: AppColors.textSecondary,
                      fontSize: 13,
                    ),
                  ),
                  const SizedBox(height: 48),
                  // 服务器地址（只读）
                  _buildServerField(),
                  const SizedBox(height: 16),
                  // 用户名
                  TextFormField(
                    controller: _usernameCtrl,
                    decoration: const InputDecoration(
                      labelText: '用户名',
                      prefixIcon: Icon(
                        Icons.person_outline,
                        color: AppColors.textSecondary,
                        size: 20,
                      ),
                    ),
                    style: const TextStyle(color: AppColors.textPrimary),
                    textInputAction: TextInputAction.next,
                    validator: (v) =>
                        v == null || v.trim().isEmpty ? '请输入用户名' : null,
                  ),
                  const SizedBox(height: 16),
                  // 密码
                  TextFormField(
                    controller: _passwordCtrl,
                    obscureText: _obscurePassword,
                    decoration: InputDecoration(
                      labelText: '密码',
                      prefixIcon: const Icon(
                        Icons.lock_outline,
                        color: AppColors.textSecondary,
                        size: 20,
                      ),
                      suffixIcon: IconButton(
                        icon: Icon(
                          _obscurePassword
                              ? Icons.visibility_off_outlined
                              : Icons.visibility_outlined,
                          color: AppColors.textSecondary,
                          size: 20,
                        ),
                        onPressed: () =>
                            setState(() => _obscurePassword = !_obscurePassword),
                      ),
                    ),
                    style: const TextStyle(color: AppColors.textPrimary),
                    textInputAction: TextInputAction.next,
                    validator: (v) =>
                        v == null || v.isEmpty ? '请输入密码' : null,
                  ),
                  const SizedBox(height: 16),
                  // 验证码
                  _buildCaptchaField(),
                  const SizedBox(height: 12),
                  // 记住登录
                  Row(
                    children: [
                      GestureDetector(
                        onTap: () =>
                            setState(() => _rememberLogin = !_rememberLogin),
                        child: Row(
                          children: [
                            SizedBox(
                              width: 20,
                              height: 20,
                              child: Checkbox(
                                value: _rememberLogin,
                                onChanged: (v) =>
                                    setState(() => _rememberLogin = v ?? false),
                                activeColor: AppColors.primary,
                                side: const BorderSide(
                                  color: AppColors.textMuted,
                                ),
                              ),
                            ),
                            const SizedBox(width: 8),
                            const Text(
                              '记住登录',
                              style: TextStyle(
                                color: AppColors.textSecondary,
                                fontSize: 13,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 24),
                  // 错误提示
                  if (state.error != null)
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 12,
                      ),
                      margin: const EdgeInsets.only(bottom: 16),
                      decoration: BoxDecoration(
                        color: AppColors.alarm.withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(
                          color: AppColors.alarm.withValues(alpha: 0.3),
                        ),
                      ),
                      child: Row(
                        children: [
                          const Icon(
                            Icons.error_outline,
                            color: AppColors.alarm,
                            size: 16,
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              state.error!,
                              style: const TextStyle(
                                color: AppColors.alarm,
                                fontSize: 13,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  // 登录按钮
                  _buildLoginButton(state),
                  const SizedBox(height: 40),
                  // 底部说明
                  const Text(
                    '移动执法监控系统  ·  仅供授权人员使用',
                    style: TextStyle(
                      color: AppColors.textMuted,
                      fontSize: 12,
                    ),
                  ),
                  const SizedBox(height: 24),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildLogo() {
    return Container(
      width: 80,
      height: 80,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF1F6FEB), Color(0xFF0D4C9E)],
        ),
        boxShadow: [
          BoxShadow(
            color: AppColors.primary.withValues(alpha: 0.3),
            blurRadius: 20,
            spreadRadius: 2,
          ),
        ],
      ),
      child: const Icon(
        Icons.videocam,
        color: Colors.white,
        size: 40,
      ),
    );
  }

  Widget _buildServerField() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.surfaceVariant),
      ),
      child: Row(
        children: [
          const Icon(
            Icons.dns_outlined,
            color: AppColors.textSecondary,
            size: 20,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              AppConfig.instance.serverUrl,
              style: const TextStyle(
                color: AppColors.textMuted,
                fontSize: 14,
              ),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const Icon(
            Icons.lock_outline,
            color: AppColors.textMuted,
            size: 14,
          ),
        ],
      ),
    );
  }

  Widget _buildCaptchaField() {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: TextFormField(
            controller: _captchaCtrl,
            decoration: const InputDecoration(
              labelText: '验证码',
              prefixIcon: Icon(
                Icons.shield_outlined,
                color: AppColors.textSecondary,
                size: 20,
              ),
            ),
            style: const TextStyle(color: AppColors.textPrimary),
            textInputAction: TextInputAction.done,
            onFieldSubmitted: (_) => _handleLogin(),
            validator: (v) =>
                v == null || v.trim().isEmpty ? '请输入验证码' : null,
          ),
        ),
        const SizedBox(width: 12),
        // 验证码图片，点击刷新
        GestureDetector(
          onTap: _captchaLoading ? null : _refreshCaptcha,
          child: Container(
            width: 110,
            height: 50,
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: AppColors.surfaceVariant),
            ),
            alignment: Alignment.center,
            clipBehavior: Clip.antiAlias,
            child: _captchaLoading
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      valueColor: AlwaysStoppedAnimation(AppColors.primary),
                    ),
                  )
                : _captchaImage != null
                    ? Image.memory(
                        _captchaImage!,
                        fit: BoxFit.contain,
                        gaplessPlayback: true,
                      )
                    : const Text(
                        '点击获取',
                        style: TextStyle(
                          color: Color(0xFF666666),
                          fontSize: 12,
                        ),
                      ),
          ),
        ),
      ],
    );
  }

  Widget _buildLoginButton(AuthState state) {
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(8),
        gradient: state.isLoading
            ? null
            : const LinearGradient(
                colors: [Color(0xFF1F6FEB), Color(0xFF1158C7)],
              ),
        color: state.isLoading ? AppColors.surfaceVariant : null,
      ),
      child: ElevatedButton(
        onPressed: state.isLoading ? null : _handleLogin,
        style: ElevatedButton.styleFrom(
          backgroundColor: Colors.transparent,
          shadowColor: Colors.transparent,
          minimumSize: const Size(double.infinity, 50),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(8),
          ),
        ),
        child: state.isLoading
            ? const SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  valueColor: AlwaysStoppedAnimation(AppColors.textSecondary),
                ),
              )
            : const Text(
                '登 录',
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 4,
                ),
              ),
      ),
    );
  }
}
