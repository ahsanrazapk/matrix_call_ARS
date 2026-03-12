import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart' as webrtc;
import 'package:matrix/matrix.dart';
import 'package:matrix_call/main.dart';
import 'package:matrix_call/services/call_manager.dart';
import 'package:matrix_call/voip/vivoka_sdk.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter_ringtone_player/flutter_ringtone_player.dart';
import 'package:torch_light/torch_light.dart';
import 'package:webrtc_interface/webrtc_interface.dart';

import 'call_screen.dart';

class VoipService extends ChangeNotifier
    implements WebRTCDelegate, WidgetsBindingObserver {
  final Client client;
  VoipService(this.client);

  VoIP? _voip;

  CallSession? _active;
  CallSession? _connectedCall;

  double remoteZoom = 1.0;

  bool _started = false;

  bool get hasCall => _active != null;
  CallSession? get active => _active;

  final webrtc.RTCVideoRenderer localRenderer = webrtc.RTCVideoRenderer();
  final webrtc.RTCVideoRenderer remoteRenderer = webrtc.RTCVideoRenderer();
  final webrtc.RTCVideoRenderer remoteScreenRenderer =
      webrtc.RTCVideoRenderer();

  bool micMuted = false;
  bool camMuted = false;

  StreamSubscription? _stateSub;
  StreamSubscription? _streamsSub;
  StreamSubscription? _eventSub;
  StreamSubscription? _replacedSub;
  StreamSubscription? _hangupForGroupSub;
  StreamSubscription? _streamAddSub;
  StreamSubscription? _streamRemovedSub;
  StreamSubscription? _zoomSub;

  StreamSubscription? _groupInviteSub;
  GroupCallSession? _groupCall;

  DateTime? _connectedAt;
  DateTime? get connectedAt => _connectedAt;

  String? connectionLostMessage;
  Timer? _connectionRecoveryTimer;

  bool _ringtonePlaying = false;
  bool _ringbackPlaying = false;

  Future<void> start() async {
    if (_started) return;
    _started = true;

    WidgetsBinding.instance.addObserver(this);

    await localRenderer.initialize();
    await remoteRenderer.initialize();
    await remoteScreenRenderer.initialize();

    _voip = VoIP(client, this);

    _groupInviteSub = _voip!.onIncomingGroupCall.stream.listen((g) async {
      await handleNewGroupCall(g);
    });

    notifyListeners();
  }

  Future<void> stop() async {
    _cancelConnectionRecoveryTimer();
    connectionLostMessage = null;
    WidgetsBinding.instance.removeObserver(this);

    await stopRingtone();
    await stopRingback();

    await _groupInviteSub?.cancel();
    _groupInviteSub = null;

    final s = _active;
    if (s != null && s.state != CallState.kEnded) {
      try {
        await s.hangup(reason: CallErrorCode.userHangup);
      } catch (_) {}
    }

    _connectedCall = null;

    await _clearPerCallSubs();

    localRenderer.srcObject = null;
    remoteRenderer.srcObject = null;
    remoteScreenRenderer.srcObject = null;

    _groupCall = null;
    _voip = null;
    _active = null;
    _started = false;

    try {
      await VivokaSdkFlutter.setCallMode(false);
    } catch (_) {}

    CallManager.instance.hideIncomingCall();

    notifyListeners();
  }

  // ── App lifecycle ────────────────────────────────────────────────────────

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    switch (state) {
      case AppLifecycleState.paused:
        _onAppPaused();
      case AppLifecycleState.resumed:
        _onAppResumed();
      default:
        break;
    }
  }

  void _onAppPaused() {
    if (!_started) return;
    if (_connectedCall == null) {
      try {
        VivokaSdkFlutter.stopVivoka();
      } catch (_) {}
    }
  }

  Future<void> _onAppResumed() async {
    if (!_started) return;

    final session = _active;

    // Case 3: No active call — nothing to restore
    if (session == null) return;

    // Case 3: Call ended while the app was backgrounded
    if (session.state == CallState.kEnded) {
      await clearActive();
      return;
    }

    // Resume Vivoka ASR only when not in a connected call
    if (_connectedCall == null) {
      try {
        VivokaSdkFlutter.resumeVivoka();
      } catch (_) {}
    }

    // Case 1: Incoming call is still ringing — re-show the overlay if it was dismissed
    if (session.direction == CallDirection.kIncoming &&
        session.state == CallState.kRinging) {
      if (!CallManager.instance.isShowingOverlay) {
        final callerName = session.room.getLocalizedDisplayname();
        CallManager.instance.showIncomingCall(
          session: session,
          callerName: callerName,
          onAnswer: () => answer(),
          onReject: () => reject(),
        );
      }
      return;
    }

    // Case 2: Ongoing call — reattach streams in case renderers lost their source
    if (session.state == CallState.kConnected ||
        session.state == CallState.kConnecting) {
      _reattachStreams(session);
      notifyListeners();
    }
  }

  void _reattachStreams(CallSession session) {
    final local = session.localUserMediaStream;
    final remoteScreen = session.remoteScreenSharingStream;
    final remoteUser = session.remoteUserMediaStream;

    if (local?.stream != null) {
      localRenderer.srcObject = local!.stream;
    }
    if (remoteScreen?.stream != null) {
      remoteScreenRenderer.srcObject = remoteScreen!.stream;
    } else {
      remoteScreenRenderer.srcObject = null;
    }
    if (remoteUser?.stream != null) {
      remoteRenderer.srcObject = remoteUser!.stream;
    } else {
      remoteRenderer.srcObject = null;
    }
  }

  Future<void> callUser({
    required String roomId,
    required String userId,
    required CallType type,
  }) async {
    final voip = _voip;
    if (voip == null) throw Exception('VoIP not started');

    final room = client.getRoomById(roomId);
    if (room == null) throw Exception('Room not found: $roomId');

    await _ensurePermissions(type);

    await voip.inviteToCall(room, type, userId: userId);
  }

  Future<void> answer() async {
    final s = _active;
    if (s == null) return;
    await _ensurePermissions(s.type);
    await s.answer();
  }

  Future<void> reject() async {
    final s = _active;
    if (s == null) return;
    try {
      await s.reject();
    } catch (_) {}
  }

  Future<void> hangup() async {
    final s = _active;
    if (s == null) return;
    try {
      await s.hangup(reason: CallErrorCode.userHangup);
    } catch (_) {}
  }

  Future<void> toggleMic() async {
    final s = _active;
    if (s == null) return;
    micMuted = !micMuted;
    await s.setMicrophoneMuted(micMuted);
    notifyListeners();
  }

  Future<void> muteMic(bool mute) async {
    final s = _active;
    if (s == null) return;
    micMuted = mute;
    await s.setMicrophoneMuted(mute);
    notifyListeners();
  }

  Future<void> toggleCam() async {
    final s = _active;
    if (s == null) return;
    camMuted = !camMuted;
    await s.setLocalVideoMuted(camMuted);
    notifyListeners();
  }

  void _markConnectedIfNeeded(CallSession? s) {
    if (s == null) return;
    if (_connectedAt != null) return;
    if (s.state == CallState.kConnected) {
      _connectedAt = DateTime.now();
      notifyListeners();
    }
  }

  void _resetTimer() {
    _connectedAt = null;
  }

  void _startConnectionRecoveryTimer(String message, {required int timeoutSeconds}) {
    if (_connectionRecoveryTimer != null) return;
    connectionLostMessage = message;
    notifyListeners();
    _connectionRecoveryTimer = Timer(Duration(seconds: timeoutSeconds), () {
      _onConnectionLost('Connection lost. The call has ended.');
    });
  }

  void _cancelConnectionRecoveryTimer() {
    _connectionRecoveryTimer?.cancel();
    _connectionRecoveryTimer = null;
  }

  Future<void> _onConnectionLost(String message) async {
    _cancelConnectionRecoveryTimer();
    connectionLostMessage = message;
    notifyListeners();
    await Future.delayed(const Duration(seconds: 2));
    final s = _active;
    if (s == null) return;
    try {
      await s.hangup(reason: CallErrorCode.userHangup);
    } catch (_) {
      await clearActive();
    }
  }

  @override
  MediaDevices get mediaDevices => _CustomMediaDevices();

  @override
  Future<RTCPeerConnection> createPeerConnection(
    Map<String, dynamic> configuration, [
    Map<String, dynamic> constraints = const {},
  ]) async {
    final pc = await webrtc.createPeerConnection(configuration, constraints);

    try {
      await pc.setConfiguration({
        ...configuration,
        'sdpSemantics': 'unified-plan',
      });
    } catch (_) {}

    pc.onIceConnectionState = (state) {
      debugPrint('[VoIP] ICE state: ${state.name}');
      switch (state) {
        case RTCIceConnectionState.RTCIceConnectionStateConnected:
        case RTCIceConnectionState.RTCIceConnectionStateCompleted:
          _markConnectedIfNeeded(_active);
          _tuneVideoSenders(pc);
          _cancelConnectionRecoveryTimer();
          connectionLostMessage = null;
          notifyListeners();
        case RTCIceConnectionState.RTCIceConnectionStateDisconnected:
          _startConnectionRecoveryTimer(
            'Connection unstable — attempting to reconnect…',
            timeoutSeconds: 10,
          );
        case RTCIceConnectionState.RTCIceConnectionStateFailed:
          _onConnectionLost('Connection lost. The call has ended.');
        default:
          break;
      }
    };

    pc.onConnectionState = (state) {
      debugPrint('[VoIP] Peer connection: ${state.name}');
      switch (state) {
        case RTCPeerConnectionState.RTCPeerConnectionStateConnected:
          _tuneVideoSenders(pc);
          _cancelConnectionRecoveryTimer();
          connectionLostMessage = null;
          notifyListeners();
        case RTCPeerConnectionState.RTCPeerConnectionStateDisconnected:
          _startConnectionRecoveryTimer(
            'Connection unstable — attempting to reconnect…',
            timeoutSeconds: 10,
          );
        case RTCPeerConnectionState.RTCPeerConnectionStateFailed:
          _onConnectionLost('Connection failed. The call has ended.');
        default:
          break;
      }
    };

    return pc;
  }

  Future<void> _tuneVideoSenders(RTCPeerConnection pc) async {
    try {
      final senders = await pc.getSenders();
      int? w;
      int? h;
      final stream = localRenderer.srcObject;
      final tracks = stream?.getVideoTracks() ?? const [];
      if (tracks.isNotEmpty) {
        try {
          final settings = tracks.first.getSettings();
          w = (settings['width'] as num?)?.toInt();
          h = (settings['height'] as num?)?.toInt();
        } catch (_) {}
      }
      final maxBitrate = _pickBitrate(w, h);

      for (final s in senders) {
        final track = s.track;
        if (track != null && track.kind == 'video') {
          final p = s.parameters;
          p.encodings ??= [RTCRtpEncoding()];
          p.encodings![0].maxBitrate = maxBitrate;
          p.encodings![0].maxFramerate = 30;
          await s.setParameters(p);
          debugPrint(
            '🎥 Set maxBitrate=$maxBitrate for ${w ?? "?"}x${h ?? "?"}',
          );
        }
      }
    } catch (e) {
      debugPrint('Bitrate tune failed: $e');
    }
  }

  int _pickBitrate(int? w, int? h) {
    final pixels = (w ?? 1280) * (h ?? 720);
    if (pixels >= 1920 * 1080) return 3_500_000;
    if (pixels >= 1280 * 720) return 2_200_000;
    return 900_000;
  }

  @override
  Future<void> playRingtone() async {
    if (_ringtonePlaying) return;
    _ringtonePlaying = true;
    try {
      await FlutterRingtonePlayer().play(
        android: AndroidSounds.ringtone,
        ios: IosSounds.electronic,
        looping: true,
        volume: 1.0,
        asAlarm: false,
      );
    } catch (e) {
      debugPrint('Ringtone play failed: $e');
    }
  }

  @override
  Future<void> stopRingtone() async {
    if (!_ringtonePlaying) return;
    try {
      await FlutterRingtonePlayer().stop();
    } catch (_) {}
    _ringtonePlaying = false;
  }

  Future<void> playRingback() async {
    if (_ringbackPlaying) return;
    _ringbackPlaying = true;
    try {
      await FlutterRingtonePlayer().play(
        android: AndroidSounds.notification,
        ios: IosSounds.triTone,
        looping: true,
        volume: 0.7,
        asAlarm: false,
      );
    } catch (e) {
      debugPrint('Ringback play failed: $e');
    }
  }

  Future<void> stopRingback() async {
    if (!_ringbackPlaying) return;
    try {
      await FlutterRingtonePlayer().stop();
    } catch (_) {}
    _ringbackPlaying = false;
  }

  @override
  bool get isWeb => kIsWeb;

  @override
  bool get canHandleNewCall => _connectedCall == null;

  @override
  EncryptionKeyProvider? get keyProvider => null;

  @override
  Future<void> registerListeners(CallSession session) async {
    if (_active?.callId == session.callId && _stateSub != null) return;

    await _clearPerCallSubs();

    _stateSub = session.onCallStateChanged.stream.listen((_) async {
      debugPrint(
        '[VoIP] '
        'callId=${session.callId} | '
        'dir=${session.direction.name} | '
        'state=${session.state.name} | '
        'reason=${session.hangupReason?.name ?? 'none'}',
      );

      if (session.direction == CallDirection.kIncoming) {
        if (session.state == CallState.kRinging) {
          await playRingtone();
        } else {
          await stopRingtone();
        }
      }

      if (session.direction == CallDirection.kOutgoing) {
        final shouldRingback =
            session.state == CallState.kInviteSent ||
            session.state == CallState.kCreateOffer ||
            session.state == CallState.kConnecting ||
            session.state == CallState.kRinging;

        if (shouldRingback) {
          await playRingback();
        } else {
          await stopRingback();
        }
      }

      if (session.state == CallState.kConnected) {
        _connectedCall = session;
        _markConnectedIfNeeded(session);
        await stopRingtone();
        await stopRingback();
        try {
          await VivokaSdkFlutter.setCallMode(true);
        } catch (_) {}
      }

      if (session.state == CallState.kEnded) {
        await _clearActiveIfSame(session);
        return;
      }

      notifyListeners();
    });

    _streamsSub = session.onCallStreamsChanged.stream.listen((_) {
      final local = session.localUserMediaStream;
      final remoteScreen = session.remoteScreenSharingStream;
      final remoteUser = session.remoteUserMediaStream;

      if (local?.stream != null) {
        localRenderer.srcObject = local!.stream;
      }
      if (remoteScreen?.stream != null) {
        remoteScreenRenderer.srcObject = remoteScreen!.stream;
      } else {
        remoteScreenRenderer.srcObject = null;
      }
      if (remoteUser?.stream != null) {
        remoteRenderer.srcObject = remoteUser!.stream;
      } else {
        remoteRenderer.srcObject = null;
      }

      notifyListeners();
    });

    _eventSub = session.onCallEventChanged.stream.listen((_) {});
    _replacedSub = session.onCallReplaced.stream.listen((_) {});
    _hangupForGroupSub =
        session.onCallHangupNotifierForGroupCalls.stream.listen((_) {});
    _streamAddSub = session.onStreamAdd.stream.listen((_) {});
    _streamRemovedSub = session.onStreamRemoved.stream.listen((_) {});
  }

  @override
  Future<void> handleNewCall(CallSession session) async {
    if (!canHandleNewCall &&
        session.direction == CallDirection.kIncoming &&
        session.state == CallState.kRinging) {
      await handleMissedCall(session);
      return;
    }

    if (_active != null && _active!.callId != session.callId) {
      await session.reject();
      return;
    }

    _active = session;
    micMuted = false;
    camMuted = false;

    notifyListeners();

    if (session.direction == CallDirection.kIncoming &&
        session.state == CallState.kRinging) {
      await playRingtone();

      final callerName = session.room.getLocalizedDisplayname();

      CallManager.instance.showIncomingCall(
        session: session,
        callerName: callerName,
        onAnswer: () => answer(),
        onReject: () => reject(),
      );
    }
  }

  @override
  Future<void> handleNewGroupCall(GroupCallSession groupCall) async {
    _groupCall = groupCall;
    notifyListeners();
  }

  @override
  Future<void> handleGroupCallEnded(GroupCallSession groupCall) async {
    if (_groupCall == groupCall) _groupCall = null;
    notifyListeners();
  }

  @override
  Future<void> handleCallEnded(CallSession session) async {
    await _clearActiveIfSame(session);
    notifyListeners();
  }

  @override
  Future<void> handleMissedCall(CallSession session) async {
    try {
      await session.reject();
    } catch (_) {}
    await _clearActiveIfSame(session);
    notifyListeners();
  }

  Future<void> _clearActiveIfSame(CallSession session) async {
    final active = _active;

    debugPrint(
      '[VoipService] clearIfSame | active=${active?.callId ?? "null"} | '
      'ended=${session.callId} | reason=${session.hangupReason?.name ?? "none"}',
    );

    if (active?.callId != session.callId) return;

    await clearActive();
  }

  Future<void> clearActive() async {
    _cancelConnectionRecoveryTimer();
    connectionLostMessage = null;
    await stopRingtone();
    await stopRingback();
    await _clearPerCallSubs();
    _resetTimer();

    _active = null;
    _connectedCall = null;
    localRenderer.srcObject = null;
    remoteRenderer.srcObject = null;
    remoteScreenRenderer.srcObject = null;

    try {
      await VivokaSdkFlutter.setCallMode(false);
    } catch (_) {}

    CallManager.instance.hideIncomingCall();

    await _popCallScreenIfOpen();

    notifyListeners();
  }

  Future<void> _clearPerCallSubs() async {
    await _stateSub?.cancel();
    await _streamsSub?.cancel();
    await _eventSub?.cancel();
    await _replacedSub?.cancel();
    await _hangupForGroupSub?.cancel();
    await _streamAddSub?.cancel();
    await _streamRemovedSub?.cancel();
    await _zoomSub?.cancel();

    _stateSub = null;
    _streamsSub = null;
    _eventSub = null;
    _replacedSub = null;
    _hangupForGroupSub = null;
    _streamAddSub = null;
    _streamRemovedSub = null;
    _zoomSub = null;
  }

  Future<void> _popCallScreenIfOpen() async {
    final nav = appNavigatorKey.currentState;
    if (nav == null) return;

    nav.popUntil((route) => route.settings.name != CallScreen.route);
  }

  Future<void> _ensurePermissions(CallType type) async {
    final mic = await Permission.microphone.request();
    if (!mic.isGranted) throw Exception('Microphone permission denied');

    if (type == CallType.kVideo) {
      final cam = await Permission.camera.request();
      if (!cam.isGranted) throw Exception('Camera permission denied');
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _groupInviteSub?.cancel();
    _zoomSub?.cancel();
    _clearPerCallSubs();
    try {
      localRenderer.dispose();
      remoteRenderer.dispose();
      remoteScreenRenderer.dispose();
    } catch (_) {}
    super.dispose();
  }

  Future<void> setLocalCameraZoom(double zoom) async {
    final stream = localRenderer.srcObject;
    if (stream == null) return;
    final tracks = stream.getVideoTracks();
    if (tracks.isEmpty) return;

    final track = tracks.first;
    try {
      await webrtc.Helper.setZoom(track, zoom);
      remoteZoom = zoom;
    } catch (e) {
      debugPrint('Zoom not supported: $e');
    }
  }

  Future<void> setLocalCameraZoomWithCMD(String cmd) async {
    double newZoom = remoteZoom;
    if (cmd == 'zoom in') newZoom = (remoteZoom + 1.0).clamp(1.0, 5.0);
    if (cmd == 'zoom out') newZoom = (remoteZoom - 1.0).clamp(1.0, 5.0);
    if (newZoom != remoteZoom) {
      setLocalCameraZoom(newZoom);
    }
  }

  Future<void> flash(bool torch) async => VivokaSdkFlutter.toggleTorch(torch);

  Future<void> on() async {
    try {
      await TorchLight.enableTorch();
    } on Exception catch (_) {}
  }

  Future<void> off() async {
    try {
      await TorchLight.disableTorch();
    } on Exception catch (_) {}
  }
}

class _CustomMediaDevices implements MediaDevices {
  final MediaDevices _native = webrtc.navigator.mediaDevices;

  @override
  Future<webrtc.MediaStream> getUserMedia(
    Map<String, dynamic> constraints,
  ) async {
    final c = Map<String, dynamic>.from(constraints);
    if (c['video'] == true || c['video'] is Map) {
      final existingVideo = c['video'] is Map
          ? Map<String, dynamic>.from(c['video'] as Map)
          : <String, dynamic>{};
      final deviceId = existingVideo['deviceId'];
      final facingMode = existingVideo['facingMode'] ?? 'environment';

      final attempts = <Map<String, dynamic>>[
        _videoConstraints(existingVideo, facingMode, deviceId, 1920, 1080, 24),
        _videoConstraints(existingVideo, facingMode, deviceId, 1280, 720, 30),
        _videoConstraints(existingVideo, facingMode, deviceId, 640, 480, 30),
      ];

      Object? lastError;

      for (final video in attempts) {
        try {
          final merged = Map<String, dynamic>.from(c);
          merged['video'] = video;
          final stream = await _native.getUserMedia(merged);
          await _logActualCapture(stream);
          return stream;
        } catch (e) {
          lastError = e;
        }
      }

      throw lastError ?? Exception('getUserMedia failed for all resolutions');
    }

    return _native.getUserMedia(c);
  }

  Map<String, dynamic> _videoConstraints(
    Map<String, dynamic> existingVideo,
    String facingMode,
    dynamic deviceId,
    int width,
    int height,
    int fps,
  ) {
    final out = <String, dynamic>{
      ...existingVideo,
      'facingMode': facingMode,
      'width': {'ideal': width, 'max': width},
      'height': {'ideal': height, 'max': height},
      'frameRate': {'ideal': fps, 'max': fps},
      'aspectRatio': {'ideal': width / height},
    };
    if (deviceId != null) {
      out['deviceId'] = deviceId;
      out.remove('facingMode');
    }
    return out;
  }

  Future<void> _logActualCapture(webrtc.MediaStream stream) async {
    try {
      final videoTracks = stream.getVideoTracks();
      if (videoTracks.isEmpty) return;
      final t = videoTracks.first;
      final settings = t.getSettings();
      final w = settings['width'];
      final h = settings['height'];
      final fps = settings['frameRate'];
      debugPrint('📷 Capture settings => ${w}x$h @ $fps fps');
    } catch (_) {}
  }

  @override
  Future<webrtc.MediaStream> getDisplayMedia(
    Map<String, dynamic> constraints,
  ) {
    return _native.getDisplayMedia(constraints);
  }

  @override
  Future<List<webrtc.MediaDeviceInfo>> enumerateDevices() {
    return _native.enumerateDevices();
  }

  @override
  Future<List<dynamic>> getSources() {
    return _native.enumerateDevices();
  }

  @override
  webrtc.MediaTrackSupportedConstraints getSupportedConstraints() {
    return _native.getSupportedConstraints();
  }

  @override
  Future<webrtc.MediaDeviceInfo> selectAudioOutput([
    webrtc.AudioOutputOptions? options,
  ]) {
    return _native.selectAudioOutput(options);
  }

  @override
  Function(dynamic)? ondevicechange;
}
