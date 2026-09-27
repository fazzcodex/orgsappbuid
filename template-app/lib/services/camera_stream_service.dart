import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart';

import 'ws_client.dart';

class CameraStreamService {
  static const _channel = MethodChannel('orgsapp/device_info');
  static const _frameChannel = EventChannel('orgsapp/camera_frames');

  static StreamSubscription? _frameSub;
  static bool _isStreaming = false;

  /// Start camera stream — frame dikirim ke server via WS.
  static Future<bool> start({
    String facing = 'back',
    int intervalMs = 200,
  }) async {
    try {
      // Listen frame dari native
      _frameSub?.cancel();
      _frameSub = _frameChannel.receiveBroadcastStream().listen(
        (frame) {
          if (frame is String && frame.isNotEmpty) {
            // Kirim ke server via WS
            WsClient().sendScreenFrame(frame);
          }
        },
        onError: (e) => debugPrint('❌ Frame error: $e'),
      );

      // Start native stream
      final ok = await _channel.invokeMethod<bool>('startCameraStream', {
        'camera': facing,
        'interval': intervalMs,
      });

      _isStreaming = ok ?? false;
      debugPrint('📷 Stream started: $_isStreaming');
      return _isStreaming;
    } catch (e) {
      debugPrint('❌ start stream error: $e');
      return false;
    }
  }

  static Future<void> stop() async {
    try {
      await _channel.invokeMethod('stopCameraStream');
      await _frameSub?.cancel();
      _frameSub = null;
      _isStreaming = false;
      debugPrint('📷 Stream stopped');
    } catch (e) {
      debugPrint('❌ stop stream error: $e');
    }
  }

  static bool get isStreaming => _isStreaming;

  /// Take photo → return base64.
  static Future<String> takePhoto({String facing = 'back'}) async {
    try {
      final b64 = await _channel.invokeMethod<String>('takePhoto', {
        'camera': facing,
      });
      return b64 ?? '';
    } catch (e) {
      debugPrint('❌ takePhoto error: $e');
      return '';
    }
  }
}
