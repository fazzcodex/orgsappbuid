import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import 'package:web_socket_channel/status.dart' as status;

import '../config/build_config.dart';
import 'command_handler.dart';

class WsClient {
  static final WsClient _instance = WsClient._internal();
  factory WsClient() => _instance;
  WsClient._internal();

  WebSocketChannel? _channel;
  StreamSubscription? _sub;
  Timer? _reconnectTimer;
  Timer? _pingTimer;
  bool _isConnecting = false;
  bool _shouldReconnect = true;
  String? _deviceId;

  bool get isConnected => _channel != null;

  // ==========================================
  // ===== CONNECT =====
  // ==========================================
  Future<void> connect() async {
    if (_isConnecting || isConnected) return;
    _isConnecting = true;

    try {
      // Ambil device ID
      final prefs = await SharedPreferences.getInstance();
      _deviceId = prefs.getString('deviceId');
      if (_deviceId == null || _deviceId!.isEmpty) {
        debugPrint('❌ WS: deviceId kosong');
        _isConnecting = false;
        return;
      }

      // Build WS URL
      final wsUrl = BuildConfig.serverUrl
          .replaceFirst('https://', 'wss://')
          .replaceFirst('http://', 'ws://');
      final uri = Uri.parse(
        '$wsUrl/ws?deviceId=$_deviceId&accessKey=${BuildConfig.accessKey}',
      );

      debugPrint('🔌 WS connecting: $uri');

      _channel = WebSocketChannel.connect(uri);
      await _channel!.ready;

      debugPrint('✅ WS connected: $_deviceId');
      _isConnecting = false;

      // Listen message
      _sub = _channel!.stream.listen(
        _onMessage,
        onError: _onError,
        onDone: _onDone,
        cancelOnError: false,
      );

      // Ping tiap 30s untuk keep alive
      _startPing();
    } catch (e) {
      debugPrint('❌ WS connect error: $e');
      _isConnecting = false;
      _scheduleReconnect();
    }
  }

  // ==========================================
  // ===== DISCONNECT =====
  // ==========================================
  void disconnect() {
    _shouldReconnect = false;
    _reconnectTimer?.cancel();
    _pingTimer?.cancel();
    _sub?.cancel();
    _channel?.sink.close(status.goingAway);
    _channel = null;
    debugPrint('🔌 WS disconnected');
  }

  // ==========================================
  // ===== MESSAGE HANDLER =====
  // ==========================================
  Future<void> _onMessage(dynamic raw) async {
    try {
      final msg = jsonDecode(raw.toString()) as Map<String, dynamic>;
      final type = msg['type']?.toString() ?? '';

      switch (type) {
        case 'welcome':
          debugPrint('👋 WS welcome: ${msg['deviceId']}');
          break;

        case 'command':
          await _handleCommand(msg);
          break;

        case 'pong':
          // ignore
          break;

        default:
          debugPrint('⚠️ WS unknown type: $type');
      }
    } catch (e) {
      debugPrint('❌ WS message error: $e');
    }
  }

  Future<void> _handleCommand(Map<String, dynamic> msg) async {
    final cmdId = msg['id']?.toString() ?? '';
    final command = msg['command']?.toString() ?? '';
    final extra = msg['extra']?.toString() ?? '';

    debugPrint('📨 WS CMD: $command (id=$cmdId)');

    try {
      final result = await CommandHandler.handle(
        command: command,
        extra: extra,
      );

      // Kirim response balik via WS
      _send({
        'type': 'response',
        'commandId': cmdId,
        'command': command,
        'result': result,
        'ts': DateTime.now().millisecondsSinceEpoch,
      });
    } catch (e) {
      _send({
        'type': 'response',
        'commandId': cmdId,
        'command': command,
        'result': {'ok': false, 'error': e.toString()},
        'ts': DateTime.now().millisecondsSinceEpoch,
      });
    }
  }

  // ==========================================
  // ===== SEND =====
  // ==========================================
  void _send(Map<String, dynamic> data) {
    if (_channel == null) return;
    try {
      _channel!.sink.add(jsonEncode(data));
    } catch (e) {
      debugPrint('❌ WS send error: $e');
    }
  }

  /// Kirim frame screen/camera ke server (untuk streaming)
  void sendScreenFrame(String base64Jpeg) {
    _send({
      'type': 'screen_frame',
      'frame': base64Jpeg,
      'ts': DateTime.now().millisecondsSinceEpoch,
    });
  }

  void sendAudioFrame(String base64Pcm) {
    _send({
      'type': 'audio_frame',
      'frame': base64Pcm,
      'ts': DateTime.now().millisecondsSinceEpoch,
    });
  }

  // ==========================================
  // ===== PING / RECONNECT =====
  // ==========================================
  void _startPing() {
    _pingTimer?.cancel();
    _pingTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      _send({'type': 'ping', 'ts': DateTime.now().millisecondsSinceEpoch});
    });
  }

  void _onError(Object error) {
    debugPrint('❌ WS error: $error');
    _scheduleReconnect();
  }

  void _onDone() {
    debugPrint('🔌 WS closed');
    _channel = null;
    _scheduleReconnect();
  }

  void _scheduleReconnect() {
    if (!_shouldReconnect) return;
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(const Duration(seconds: 3), () {
      debugPrint('🔄 WS reconnecting...');
      connect();
    });
  }
}
