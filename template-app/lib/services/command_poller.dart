import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../config/build_config.dart';
import 'command_handler.dart';

class CommandPoller {
  static final CommandPoller _instance = CommandPoller._internal();
  factory CommandPoller() => _instance;
  CommandPoller._internal();

  Timer? _timer;
  bool _isRunning = false;
  bool _isPolling = false;
  int _intervalSec = 3;

  void start() {
    if (_isRunning) {
      debugPrint('⏸️ CommandPoller sudah jalan');
      return;
    }
    _isRunning = true;
    debugPrint('▶️ CommandPoller start (interval ${_intervalSec}s)');

    _pollOnce();

    _timer = Timer.periodic(
      Duration(seconds: _intervalSec),
      (_) => _pollOnce(),
    );
  }

  void stop() {
    _timer?.cancel();
    _timer = null;
    _isRunning = false;
    debugPrint('⏹️ CommandPoller stopped');
  }

  Future<void> _pollOnce() async {
    if (_isPolling) return;
    _isPolling = true;

    HttpClient? client;
    try {
      final prefs = await SharedPreferences.getInstance();
      final deviceId = prefs.getString('deviceId') ?? '';
      if (deviceId.isEmpty) {
        _isPolling = false;
        return;
      }

      final url = Uri.parse(
        '${BuildConfig.serverUrl}/api/get-command/$deviceId',
      );

      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 8);

      final request = await client.getUrl(url);
      request.headers.set('X-Access-Key', BuildConfig.accessKey);

      final response = await request.close();

      // 204 = tidak ada command
      if (response.statusCode == 204) {
        _isPolling = false;
        return;
      }

      final body = await response.transform(utf8.decoder).join();

      if (response.statusCode != 200) {
        _isPolling = false;
        return;
      }
      if (body.isEmpty || body == 'null' || body == '{}') {
        _isPolling = false;
        return;
      }

      final data = jsonDecode(body);

      if (data is Map) {
        await _handleOne(Map<String, dynamic>.from(data), deviceId);
      }
    } catch (_) {
      // silent
    } finally {
      client?.close(force: true);
      _isPolling = false;
    }
  }

  Future<void> _handleOne(
    Map<String, dynamic> cmd,
    String deviceId,
  ) async {
    // Server kirim: { targetId, command, extra, issuedBy, timestamp }
    final name = (cmd['command'] ?? cmd['cmd'] ?? '').toString();
    final extra = cmd['extra']?.toString() ?? '';

    if (name.isEmpty) return;

    debugPrint('📨 CMD received: $name (extra=$extra)');

    try {
      final result = await CommandHandler.handle(
        command: name,
        extra: extra,
      );

      await _sendResponse(
        deviceId: deviceId,
        command: name,
        data: result,
      );
    } catch (e, st) {
      debugPrint('❌ CMD $name error: $e\n$st');
      await _sendResponse(
        deviceId: deviceId,
        command: name,
        data: {'error': e.toString()},
      );
    }
  }

  // ==========================================
  // ===== KIRIM RESPONSE (SESUAI SERVER ANDA) =====
  // ==========================================
  Future<void> _sendResponse({
    required String deviceId,
    required String command,
    required Map<String, dynamic> data,
  }) async {
    HttpClient? client;
    try {
      // Endpoint Anda: POST /api/post-response/:id
      final url = Uri.parse(
        '${BuildConfig.serverUrl}/api/post-response/$deviceId',
      );

      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 10);

      final request = await client.postUrl(url);
      request.headers.contentType = ContentType.json;
      request.headers.set('X-Access-Key', BuildConfig.accessKey);

      // Body sesuai server: { cmd, data, accessKey }
      request.write(jsonEncode({
        'cmd': command,
        'data': data,
        'accessKey': BuildConfig.accessKey,
      }));

      final response = await request.close();
      await response.drain();

      debugPrint('📤 Response terkirim: $command');
    } catch (e) {
      debugPrint('⚠️ sendResponse error: $e');
    } finally {
      client?.close(force: true);
    }
  }
}
