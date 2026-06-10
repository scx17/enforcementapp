import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/core/http/dio_client.dart';

final authRepositoryProvider = FutureProvider<AuthRepository>((ref) async {
  final dio = await ref.watch(dioClientProvider.future);
  return AuthRepository(dio);
});

/// 登录结果：成功 + 用户信息，或失败 + 原因。
class LoginResult {
  const LoginResult.success(this.data)
      : ok = true,
        message = null;
  const LoginResult.failure(this.message)
      : ok = false,
        data = null;

  final bool ok;
  final Map<String, dynamic>? data;
  final String? message;
}

class AuthRepository {
  const AuthRepository(this._dio);

  final Dio _dio;

  /// 获取验证码图片字节。
  /// GET /api/captcha/generate → image/png，同时种下 CaptchaToken Cookie。
  /// 该 Cookie 由共享 CookieJar 自动在登录请求时带回校验。
  Future<Uint8List> fetchCaptcha() async {
    try {
      final response = await _dio.get<List<int>>(
        '/api/captcha/generate',
        queryParameters: {
          't': DateTime.now().millisecondsSinceEpoch,
        },
        options: Options(responseType: ResponseType.bytes),
      );
      return Uint8List.fromList(response.data ?? <int>[]);
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }

  /// POST /api/auth/login { username, password, captcha }
  /// 响应永远 HTTP 200：{ code: 0=成功/1=失败, message, data }
  Future<LoginResult> login(
    String username,
    String password,
    String captcha,
  ) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/auth/login',
        data: {
          'username': username,
          'password': password,
          'captcha': captcha,
        },
      );
      final body = response.data ?? {};
      final code = body['code'];
      if (code == 0) {
        final data = body['data'];
        return LoginResult.success(
          data is Map<String, dynamic> ? data : <String, dynamic>{},
        );
      }
      return LoginResult.failure(
        body['message']?.toString() ?? '登录失败',
      );
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }

  Future<void> logout() async {
    try {
      await _dio.post<dynamic>('/api/auth/logout');
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }

  /// GET /api/user/current → { code: 0, data: {...} }，未登录返回 401。
  Future<Map<String, dynamic>?> getCurrentUser() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/user/current',
      );
      final body = response.data ?? {};
      if (body['code'] == 0 && body['data'] is Map) {
        return Map<String, dynamic>.from(body['data'] as Map);
      }
      return null;
    } on DioException catch (e) {
      final ex = toAppException(e);
      if (ex.isUnauthorized) return null;
      throw ex;
    }
  }
}
