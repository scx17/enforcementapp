import 'dart:io';
import 'package:cookie_jar/cookie_jar.dart';
import 'package:path_provider/path_provider.dart';
import 'package:hdc_mobile/core/config/constants.dart';

Future<PersistCookieJar> buildCookieJar() async {
  final appDir = await getApplicationSupportDirectory();
  final cookieDir = Directory('${appDir.path}/${AppConstants.cookieDirName}');
  if (!cookieDir.existsSync()) {
    cookieDir.createSync(recursive: true);
  }
  return PersistCookieJar(storage: FileStorage(cookieDir.path));
}
