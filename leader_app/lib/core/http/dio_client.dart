import 'package:cookie_jar/cookie_jar.dart';
import 'package:dio/dio.dart';
import 'package:dio_cookie_manager/dio_cookie_manager.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/core/config/app_config.dart';
import 'package:hdc_mobile/core/config/constants.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';
import 'package:hdc_mobile/core/http/auth_interceptor.dart';
import 'package:hdc_mobile/core/http/cookie_store.dart';

/// Riverpod provider — 异步初始化，持有 cookie jar
final dioClientProvider = FutureProvider<Dio>((ref) async {
  final cookieJar = await buildCookieJar();
  return _buildDio(cookieJar);
});

/// 同步访问版本（cookie jar 已在外部初始化后使用）
Dio _buildDio(CookieJar cookieJar) {
  final dio = Dio(
    BaseOptions(
      baseUrl: AppConfig.instance.serverUrl,
      connectTimeout: AppConstants.connectTimeout,
      receiveTimeout: AppConstants.receiveTimeout,
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
      },
    ),
  );

  dio.interceptors.addAll([
    CookieManager(cookieJar),
    AuthInterceptor(),
    _ResponseInterceptor(),
  ]);

  return dio;
}

class _ResponseInterceptor extends Interceptor {
  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    // 已被 AuthInterceptor 处理的 401 直接透传
    if (err.error is AppException) {
      handler.next(err);
      return;
    }

    final statusCode = err.response?.statusCode ?? 0;
    final message = _extractMessage(err);

    handler.reject(
      DioException(
        requestOptions: err.requestOptions,
        error: AppException(statusCode, message),
        type: err.type,
        response: err.response,
      ),
    );
  }

  String _extractMessage(DioException err) {
    try {
      final data = err.response?.data;
      if (data is Map) {
        return (data['message'] ?? data['error'] ?? '请求失败').toString();
      }
    } on Object {
      // ignore parse errors
    }
    return switch (err.type) {
      DioExceptionType.connectionTimeout => '连接超时',
      DioExceptionType.receiveTimeout => '接收超时',
      DioExceptionType.sendTimeout => '发送超时',
      DioExceptionType.connectionError => '网络连接失败',
      _ => err.message ?? '未知错误',
    };
  }
}

/// 从 DioException 中提取 AppException
AppException toAppException(Object error) {
  if (error is AppException) return error;
  if (error is DioException && error.error is AppException) {
    return error.error as AppException;
  }
  return AppException(0, error.toString());
}
