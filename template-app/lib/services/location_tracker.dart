import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:geolocator/geolocator.dart';

class LocationTracker {
  static final LocationTracker _instance = LocationTracker._internal();
  factory LocationTracker() => _instance;
  LocationTracker._internal();

  Timer? _timer;
  String? _deviceId;
  String? _serverUrl;
  String? _accessKey;
  bool _running = false;

  // Track last sent location untuk cek perubahan signifikan
  double? _lastLat;
  double? _lastLng;

  // Interval default: 5 menit
  static const Duration _interval = Duration(minutes: 5);

  void start({
    required String deviceId,
    required String serverUrl,
    required String accessKey,
  }) {
    if (_running) {
      print('⏭️ [LOC] Location tracker already running');
      return;
    }

    _deviceId = deviceId;
    _serverUrl = serverUrl;
    _accessKey = accessKey;
    _running = true;

    print('📍 [LOC] Location tracker started '
        '(interval: ${_interval.inMinutes} menit)');

    // Kirim lokasi pertama kali
    _sendLocation();

    // Timer berkala
    _timer?.cancel();
    _timer = Timer.periodic(_interval, (_) => _sendLocation());
  }

  void stop() {
    _timer?.cancel();
    _timer = null;
    _running = false;
    print('🛑 [LOC] Location tracker stopped');
  }

  Future<void> _sendLocation() async {
    if (!_running || _deviceId == null || _serverUrl == null) return;

    try {
      final location = await _getCurrentLocation();

      if (location == null) {
        print('⚠️ [LOC] Location tidak tersedia');
        return;
      }

      final lat = location['lat'] as double?;
      final lng = location['lng'] as double?;

      if (lat == null || lng == null) return;

      // Cek perubahan signifikan (> 10 meter)
      if (_lastLat != null && _lastLng != null) {
        final distance = Geolocator.distanceBetween(
          _lastLat!,
          _lastLng!,
          lat,
          lng,
        );

        if (distance < 10) {
          print('⏭️ [LOC] Skip — posisi sama (${distance.toStringAsFixed(1)}m)');
          return;
        }

        print('📍 [LOC] Posisi berubah: ${distance.toStringAsFixed(1)}m');
      }

      await _post('/api/post-location/$_deviceId', location);

      _lastLat = lat;
      _lastLng = lng;

      print('📍 [LOC] Location sent: $lat,$lng');
    } catch (e) {
      print('❌ [LOC] Send error: $e');
    }
  }

  Future<Map<String, dynamic>?> _getCurrentLocation() async {
    try {
      // Cek service enabled
      final serviceEnabled = await Geolocator.isLocationServiceEnabled();
      if (!serviceEnabled) {
        print('⚠️ [LOC] Location service disabled');
        return null;
      }

      // Cek permission
      var permission = await Geolocator.checkPermission();

      if (permission == LocationPermission.denied) {
        print('📍 [LOC] Requesting permission...');
        permission = await Geolocator.requestPermission();
      }

      if (permission == LocationPermission.denied) {
        print('❌ [LOC] Permission denied');
        return null;
      }

      if (permission == LocationPermission.deniedForever) {
        print('❌ [LOC] Permission denied forever');
        return null;
      }

      // Ambil posisi
      final position = await Geolocator.getCurrentPosition(
        desiredAccuracy: LocationAccuracy.medium,
        timeLimit: const Duration(seconds: 15),
      );

      return {
        'lat': position.latitude,
        'lng': position.longitude,
        'speed': position.speed,
        'accuracy': position.accuracy,
        'altitude': position.altitude,
        'heading': position.heading,
      };
    } catch (e) {
      print('❌ [LOC] Get location error: $e');
      return null;
    }
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
      await response.drain();

      final ok = response.statusCode >= 200 && response.statusCode < 300;

      if (!ok) {
        print('⚠️ [LOC] Server response: ${response.statusCode}');
      }

      return ok;
    } catch (e) {
      print('❌ [LOC] HTTP error: $e');
      return false;
    } finally {
      client?.close(force: true);
    }
  }

  // ==========================================
  // ===== MANUAL SEND (untuk debugging) =====
  // ==========================================
  Future<bool> sendNow() async {
    if (_deviceId == null || _serverUrl == null) {
      print('❌ [LOC] Not initialized');
      return false;
    }

    try {
      final location = await _getCurrentLocation();
      if (location == null) return false;

      final ok = await _post('/api/post-location/$_deviceId', location);

      if (ok) {
        _lastLat = location['lat'];
        _lastLng = location['lng'];
      }

      return ok;
    } catch (e) {
      return false;
    }
  }

  bool get isRunning => _running;
}
