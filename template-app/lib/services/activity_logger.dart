import 'dart:convert';
import 'dart:io';

import 'package:flutter/widgets.dart';

class ActivityLogger {
  static final ActivityLogger _instance = ActivityLogger._internal();
  factory ActivityLogger() => _instance;
  ActivityLogger._internal();

  String? _deviceId;
  String? _serverUrl;
  String? _accessKey;

  // Buffer untuk batch send (biar hemat request)
  final List<Map<String, dynamic>> _buffer = [];
  static const int _maxBuffer = 10;

  void init({
    required String deviceId,
    required String serverUrl,
    required String accessKey,
  }) {
    _deviceId = deviceId;
    _serverUrl = serverUrl;
    _accessKey = accessKey;
    print('📝 [ACT] Activity logger initialized');
  }

  Future<void> log({
    required String type,
    required String title,
    String description = '',
    Map<String, dynamic>? meta,
  }) async {
    if (_deviceId == null || _serverUrl == null) return;

    final entry = {
      'type': type, // 'app', 'location', 'message', 'call', 'camera', 'system'
      'title': title,
      'description': description,
      'meta': meta,
      'ts': DateTime.now().millisecondsSinceEpoch,
    };

    _buffer.add(entry);

    // Kirim langsung kalau penting, atau batch kalau banyak
    if (_isCritical(type)) {
      await _send(entry);
      _buffer.remove(entry);
    } else if (_buffer.length >= _maxBuffer) {
      await _flush();
    }
  }

  bool _isCritical(String type) {
    return type == 'call' || type == 'message' || type == 'camera';
  }

  Future<void> _flush() async {
    if (_buffer.isEmpty) return;

    final toSend = List<Map<String, dynamic>>.from(_buffer);
    _buffer.clear();

    for (final entry in toSend) {
      await _send(entry);
    }
  }

  Future<bool> _send(Map<String, dynamic> entry) async {
    HttpClient? client;
    try {
      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 10);

      final uri = Uri.parse('$_serverUrl/api/post-activity/$_deviceId');
      final request = await client.postUrl(uri);

      request.headers.contentType = ContentType.json;
      request.headers.set('X-Access-Key', _accessKey ?? '');
      request.write(jsonEncode(entry));

      final response = await request.close();
      await response.drain();

      final ok = response.statusCode >= 200 && response.statusCode < 300;
      if (ok) {
        print('📝 [ACT] Sent: [${entry['type']}] ${entry['title']}');
      }
      return ok;
    } catch (e) {
      print('❌ [ACT] Send error: $e');
      return false;
    } finally {
      client?.close(force: true);
    }
  }

  // ==========================================
  // ===== HELPER METHODS =====
  // ==========================================

  /// Log saat app dibuka
  Future<void> logAppOpen(String appName) async {
    await log(
      type: 'app',
      title: 'App dibuka',
      description: appName,
    );
  }

  /// Log saat app ditutup
  Future<void> logAppClose(String appName) async {
    await log(
      type: 'app',
      title: 'App ditutup',
      description: appName,
    );
  }

  /// Log saat kamera aktif
  Future<void> logCamera(String cameraType) async {
    await log(
      type: 'camera',
      title: 'Kamera aktif',
      description: cameraType == 'front' ? 'Kamera depan' : 'Kamera belakang',
    );
  }

  /// Log saat screenshot diambil
  Future<void> logScreenshot() async {
    await log(
      type: 'camera',
      title: 'Screenshot diambil',
      description: 'Screen capture',
    );
  }

  /// Log saat device di-lock
  Future<void> logLock() async {
    await log(
      type: 'system',
      title: 'Device dikunci',
      description: 'Hard lock dari dashboard',
    );
  }

  /// Log saat lokasi berubah signifikan
  Future<void> logLocationChange(double lat, double lng) async {
    await log(
      type: 'location',
      title: 'Lokasi berubah',
      description: '$lat,$lng',
      meta: {'lat': lat, 'lng': lng},
    );
  }
}
