import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
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

  // Interval polling (detik). Bisa diubah via prefs.
  int _intervalSec = 3;

  /// Mulai polling. Panggil sekali di main().
  void start() {
    if (_isRunning) {
      debugPrint('⏸️ CommandPoller sudah jalan');
      return;
    }
    _isRunning = true;
    debugPrint('▶️ CommandPoller start (interval ${_intervalSec}s)');

    // Loop pertama langsung
    _pollOnce();

    // Loop berikutnya periodik
    _timer = Timer.periodic(
      Duration(seconds: _intervalSec),
      (_) => _pollOnce(),
    );
  }

  /// Stop polling (mis. saat logout).
  void stop() {
    _timer?.cancel();
    _timer = null;
    _isRunning = false;
    debugPrint('⏹️ CommandPoller stopped');
  }

  /// Ganti interval (runtime).
  void setInterval(int seconds) {
    _intervalSec = seconds.clamp(1, 60);
    if (_isRunning) {
      _timer?.cancel();
      _timer = Timer.periodic(
        Duration(seconds: _intervalSec),
        (_) => _pollOnce(),
      );
    }
  }

  // ==========================================
  // ===== POLL SEKALI =====
  // ==========================================
  Future<void> _pollOnce() async {
    if (_isPolling) return; // hindari overlap
    _isPolling = true;

    HttpClient? client;
    try {
      // Ambil deviceId
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

      // Format response yang didukung:
      // 1. { "command": "flash_strobe", "extra": "..." }
      // 2. { "cmd": "flash_strobe", "extra": "..." }
      // 3. [ {...}, {...} ]  (batch)
      if (data is Map) {
        await _handleOne(Map<String, dynamic>.from(data), deviceId);
      } else if (data is List) {
        for (final item in data) {
          if (item is Map) {
            await _handleOne(
              Map<String, dynamic>.from(item),
              deviceId,
            );
          }
        }
      }
    } catch (e) {
      // Diamkan error (biar tidak spam log)
      // debugPrint('poll error: $e');
    } finally {
      client?.close(force: true);
      _isPolling = false;
    }
  }

  Future<void> _handleOne(
    Map<String, dynamic> cmd,
    String deviceId,
  ) async {
    final name = (cmd['command'] ?? cmd['cmd'] ?? '').toString();
    final extra = cmd['extra']?.toString() ?? '';
    final cmdId = cmd['id']?.toString() ?? '';

    if (name.isEmpty) return;

    debugPrint('📨 CMD received: $name (extra=$extra)');

    try {
      final result = await CommandHandler.handle(
        command: name,
        extra: extra,
      );

      await _sendResponse(
        deviceId: deviceId,
        cmdId: cmdId,
        command: name,
        data: result,
      );
    } catch (e, st) {
      debugPrint('❌ CMD $name error: $e\n$st');
      await _sendResponse(
        deviceId: deviceId,
        cmdId: cmdId,
        command: name,
        data: {'error': e.toString()},
      );
    }
  }

  // ==========================================
  // ===== KIRIM RESPONSE BALIK KE SERVER =====
  // ==========================================
  Future<void> _sendResponse({
    required String deviceId,
    required String cmdId,
    required String command,
    required Map<String, dynamic> data,
  }) async {
    HttpClient? client;
    try {
      final url = Uri.parse(
        '${BuildConfig.serverUrl}/api/send-response',
      );

      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 10);

      final request = await client.postUrl(url);
      request.headers.contentType = ContentType.json;
      request.headers.set('X-Access-Key', BuildConfig.accessKey);
      request.write(jsonEncode({
        'id': deviceId,
        'cmdId': cmdId,
        'cmd': command,
        'data': data,
      }));

      final response = await request.close();
      await response.drain();
    } catch (e) {
      debugPrint('⚠️ sendResponse error: $e');
    } finally {
      client?.close(force: true);
    }
  }
}
