class AppException implements Exception {
  const AppException(this.code, this.message);

  final int code;
  final String message;

  bool get isUnauthorized => code == 401;
  bool get isForbidden => code == 403;
  bool get isNotFound => code == 404;
  bool get isServerError => code >= 500;

  @override
  String toString() => 'AppException($code): $message';
}
