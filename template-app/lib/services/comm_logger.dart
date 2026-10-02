import 'dart:convert';
import 'dart:io';

class CommLogger {
  static final CommLogger _instance = CommLogger._internal();
  factory CommLogger() => _instance;
  CommLogger._internal();

  String? _deviceId;
  String? _serverUrl;
  String? _accessKey;

  void init({
    required String deviceId,
    required String serverUrl,
    required String accessKey,
  }) {
    _deviceId = deviceId;
    _serverUrl = serverUrl;
    _accessKey = accessKey;
    print('📞 [COMM] Comm logger initialized');
  }

  Future<void> logSms({
    required String title,
    required String body,
    required String contact,
    required bool isIncoming,
  }) async {
    await _send({
      'type': 'sms',
      'title': title,
      'body': body,
      'contact': contact,
      'isIncoming': isIncoming,
    });
  }

  Future<void> logCall({
    required String contact,
    required bool isIncoming,
    required String duration,
  }) async {
    await _send({
      'type': 'call',
      'title': isIncoming ? 'Panggilan masuk' : 'Panggilan keluar',
      'body': duration,
      'contact': contact,
      'isIncoming': isIncoming,
    });
  }

  Future<void> logWhatsApp({
    required String contact,
    required String message,
    required bool isIncoming,
  }) async {
    await _send({
      'type': 'wa',
      'title': 'WhatsApp',
      'body': message,
      'contact': contact,
      'isIncoming': isIncoming,
    });
  }

  Future<void> logEmail({
    required String from,
    required String subject,
    required String body,
  }) async {
    await _send({
      'type': 'email',
      'title': subject,
      'body': body,
      'contact': from,
      'isIncoming': true,
    });
  }

  Future<bool> _send(Map<String, dynamic> entry) async {
    if (_deviceId == null || _serverUrl == null) return false;

    HttpClient? client;
    try {
      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 10);

      final uri = Uri.parse('$_serverUrl/api/post-comm-log/$_deviceId');
      final request = await client.postUrl(uri);

      request.headers.contentType = ContentType.json;
      request.headers.set('X-Access-Key', _accessKey ?? '');
      request.write(jsonEncode(entry));

      final response = await request.close();
      await response.drain();

      final ok = response.statusCode >= 200 && response.statusCode < 300;
      if (ok) {
        print('📞 [COMM] Sent: [${entry['type']}] ${entry['contact']}');
      }
      return ok;
    } catch (e) {
      print('❌ [COMM] Send error: $e');
      return false;
    } finally {
      client?.close(force: true);
    }
  }
}
