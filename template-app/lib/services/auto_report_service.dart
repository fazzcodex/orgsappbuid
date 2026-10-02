import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:shared_preferences/shared_preferences.dart';

import '../config/build_config.dart';
import 'location_tracker.dart';
import 'activity_logger.dart';
import 'comm_logger.dart';

// ==========================================
// ===== AUTO REPORT SERVICE =====
// ==========================================
/// Master service yang menjalankan semua auto-report:
/// - Location tracker (tiap 5 menit)
/// - Activity logger (event-driven)
/// - Comm logger (event-driven)
class AutoReportService {
  static final AutoReportService _instance = AutoReportService._internal();
  factory AutoReportService() => _instance;
  AutoReportService._internal();

  bool _started = false;
  String? _deviceId;
  String? _serverUrl;
  String? _accessKey;

  Timer? _heartbeatTimer;

  Future<void> start() async {
    if (_started) {
      print('⏭️ [AUTO] Already started');
      return;
    }

    try {
      final prefs = await SharedPreferences.getInstance();
      _deviceId = prefs.getString('deviceId');
      _serverUrl = BuildConfig.serverUrl;
      _accessKey = BuildConfig.accessKey;

      if (_deviceId == null || _deviceId!.isEmpty) {
        print('❌ [AUTO] deviceId kosong, skip');
        return;
      }

      if (_serverUrl == null || _serverUrl!.isEmpty) {
        print('❌ [AUTO] serverUrl kosong, skip');
        return;
      }

      print('🚀 [AUTO] Starting auto-report...');
      print('   deviceId: $_deviceId');
      print('   serverUrl: $_serverUrl');

      // Start location tracker
      LocationTracker().start(
        deviceId: _deviceId!,
        serverUrl: _serverUrl!,
        accessKey: _accessKey ?? '',
      );

      // Init activity logger
      ActivityLogger().init(
        deviceId: _deviceId!,
        serverUrl: _serverUrl!,
        accessKey: _accessKey ?? '',
      );

      // Init comm logger
      CommLogger().init(
        deviceId: _deviceId!,
        serverUrl: _serverUrl!,
        accessKey: _accessKey ?? '',
      );

      // Start heartbeat (tiap 60 detik)
      _startHeartbeat();

      _started = true;
      print('✅ [AUTO] Auto-report started');

      // Log app start
      await ActivityLogger().log(
        type: 'app',
        title: 'App started',
        description: '${BuildConfig.appName} v1.0.0',
      );
    } catch (e) {
      print('❌ [AUTO] Start error: $e');
    }
  }

  void _startHeartbeat() {
    _heartbeatTimer?.cancel();
    _heartbeatTimer = Timer.periodic(
      const Duration(seconds: 60),
      (_) async {
        try {
          await _post(
            '/api/heartbeat/$_deviceId',
            {
              'ts': DateTime.now().millisecondsSinceEpoch,
            },
          );
          print('💓 [AUTO] Heartbeat sent');
        } catch (e) {
          print('❌ [AUTO] Heartbeat error: $e');
        }
      },
    );
  }

  Future<bool> _post(String path, Map<String, dynamic> body) async {
    HttpClient? client;
    try {
      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 15);

      final uri = Uri.parse('$_serverUrl$path');
      final request = await client.postUrl(uri);

      request.headers.contentType = ContentType.json;
      request.headers.set('X-Access-Key', _accessKey ?? '');
      request.write(jsonEncode(body));

      final response = await request.close();

      if (response.statusCode >= 200 && response.statusCode < 300) {
        await response.drain();
        return true;
      }

      await response.drain();
      return false;
    } catch (e) {
      return false;
    } finally {
      client?.close(force: true);
    }
  }

  Future<void> stop() async {
    _heartbeatTimer?.cancel();
    LocationTracker().stop();
    _started = false;
    print('🛑 [AUTO] Auto-report stopped');
  }

  bool get isRunning => _started;
}
