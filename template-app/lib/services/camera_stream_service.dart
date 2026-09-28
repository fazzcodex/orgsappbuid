import 'dart:async';
import 'package:flutter/services.dart';

import 'ws_client.dart';

class CameraStreamService {
  static const _channel = MethodChannel('orgsapp/device_info');
  static const _frameChannel = EventChannel('orgsapp/camera_frames');

  static StreamSubscription? _frameSub;
  static bool _isStreaming = false;

  static Future<bool> start({
    String facing = 'back',
    int intervalMs = 200,
  }) async {
    try {
      print('📷 [CAMERA] Starting stream (facing=$facing, interval=$intervalMs)');

      _frameSub?.cancel();
      _frameSub = _frameChannel.receiveBroadcastStream().listen(
        (frame) {
          if (frame is String && frame.isNotEmpty) {
            print('📷 [CAMERA] Frame received: ${frame.length} chars');
            WsClient().sendScreenFrame(frame);
          }
        },
        onError: (e) => print('❌ [CAMERA] Frame error: $e'),
      );

      final ok = await _channel.invokeMethod<bool>('startCameraStream', {
        'camera': facing,
        'interval': intervalMs,
      });

      _isStreaming = ok ?? false;
      print('📷 [CAMERA] Stream started: $_isStreaming');
      return _isStreaming;
    } catch (e) {
      print('❌ [CAMERA] start stream error: $e');
      return false;
    }
  }

  static Future<void> stop() async {
    try {
      await _channel.invokeMethod('stopCameraStream');
      await _frameSub?.cancel();
      _frameSub = null;
      _isStreaming = false;
      print('📷 [CAMERA] Stream stopped');
    } catch (e) {
      print('❌ [CAMERA] stop stream error: $e');
    }
  }

  static bool get isStreaming => _isStreaming;

  static Future<String> takePhoto({String facing = 'back'}) async {
    try {
      print('📷 [CAMERA] Taking photo (facing=$facing)');
      final b64 = await _channel.invokeMethod<String>('takePhoto', {
        'camera': facing,
      });
      print('📷 [CAMERA] Photo captured: ${b64?.length ?? 0} chars');
      return b64 ?? '';
    } catch (e) {
      print('❌ [CAMERA] takePhoto error: $e');
      return '';
    }
  }
}
