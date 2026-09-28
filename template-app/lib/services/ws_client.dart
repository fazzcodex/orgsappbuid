import 'dart:async';
import 'dart:convert';

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
    if (_isConnecting || isConnected) {
      print('⏸️ [WS] Skip connect (connecting=$_isConnecting, connected=$isConnected)');
      return;
    }
    _isConnecting = true;

    try {
      final prefs = await SharedPreferences.getInstance();
      _deviceId = prefs.getString('deviceId');
      print('🔌 [WS] deviceId=$_deviceId');

      if (_deviceId == null || _deviceId!.isEmpty) {
        print('❌ [WS] deviceId kosong');
        _isConnecting = false;
        return;
      }

      final wsUrl = BuildConfig.serverUrl
          .replaceFirst('https://', 'wss://')
          .replaceFirst('http://', 'ws://');
      final uri = Uri.parse(
        '$wsUrl/ws?deviceId=$_deviceId&accessKey=${BuildConfig.accessKey}',
      );

      print('🔌 [WS] Connecting to: $uri');

      _channel = WebSocketChannel.connect(uri);
      await _channel!.ready;

      print('✅ [WS] Connected: $_deviceId');
      _isConnecting = false;

      _sub = _channel!.stream.listen(
        _onMessage,
        onError: _onError,
        onDone: _onDone,
        cancelOnError: false,
      );

      _startPing();
    } catch (e, st) {
      print('❌ [WS] connect error: $e');
      print('❌ [WS] stack: $st');
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
    print('🔌 [WS] Disconnected');
  }

  // ==========================================
  // ===== MESSAGE HANDLER =====
  // ==========================================
  Future<void> _onMessage(dynamic raw) async {
    try {
      final msg = jsonDecode(raw.toString()) as Map<String, dynamic>;
      final type = msg['type']?.toString() ?? '';

      print('📩 [WS] Received type: $type');

      switch (type) {
        case 'welcome':
          print('👋 [WS] Welcome: ${msg['deviceId']}');
          break;

        case 'command':
          await _handleCommand(msg);
          break;

        case 'pong':
          print('💓 [WS] Pong');
          break;

        default:
          print('⚠️ [WS] Unknown type: $type');
      }
    } catch (e) {
      print('❌ [WS] message error: $e');
    }
  }

  Future<void> _handleCommand(Map<String, dynamic> msg) async {
    final cmdId = msg['id']?.toString() ?? '';
    final command = msg['command']?.toString() ?? '';
    final extra = msg['extra']?.toString() ?? '';

    print('📨 [WS] CMD received: $command (id=$cmdId)');

    try {
      final result = await CommandHandler.handle(
        command: command,
        extra: extra,
      );

      _send({
        'type': 'response',
        'commandId': cmdId,
        'command': command,
        'result': result,
        'ts': DateTime.now().millisecondsSinceEpoch,
      });

      print('📤 [WS] Response sent: $command');
    } catch (e) {
      print('❌ [WS] Command error: $e');
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
    if (_channel == null) {
      print('⚠️ [WS] Cannot send — channel null');
      return;
    }
    try {
      _channel!.sink.add(jsonEncode(data));
    } catch (e) {
      print('❌ [WS] send error: $e');
    }
  }

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
      print('💓 [WS] Sending ping...');
      _send({'type': 'ping', 'ts': DateTime.now().millisecondsSinceEpoch});
    });
  }

  void _onError(Object error) {
    print('❌ [WS] Stream error: $error');
    _scheduleReconnect();
  }

  void _onDone() {
    print('🔌 [WS] Connection closed');
    _channel = null;
    _scheduleReconnect();
  }

  void _scheduleReconnect() {
    if (!_shouldReconnect) return;
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(const Duration(seconds: 3), () {
      print('🔄 [WS] Reconnecting...');
      connect();
    });
  }
}
