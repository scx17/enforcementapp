import 'package:dio/dio.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';

class AuthInterceptor extends Interceptor {
  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    if (err.response?.statusCode == 401) {
      handler.reject(
        DioException(
          requestOptions: err.requestOptions,
          error: const AppException(401, '登录已过期，请重新登录'),
          type: DioExceptionType.badResponse,
          response: err.response,
        ),
      );
      return;
    }
    handler.next(err);
  }
}
