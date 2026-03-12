import 'package:flutter/material.dart';
import 'package:matrix/matrix.dart';

import '../dispatccher/command_dispatcher.dart';
import '../main.dart';
import '../voip/call_screen.dart';

class CallManager {
  CallManager._();
  static final CallManager instance = CallManager._();

  OverlayEntry? _overlayEntry;
  Object? _dispatcherToken;

  bool get isShowingOverlay => _overlayEntry != null;

  VoidCallback? _storedAnswer;
  VoidCallback? _storedReject;
  Room? _activeRoom;

  void showIncomingCall({
    required CallSession session,
    required String callerName,
    required VoidCallback onAnswer,
    required VoidCallback onReject,
  }) {
    _storedAnswer = onAnswer;
    _storedReject = onReject;
    _activeRoom = session.room;

    _registerGlobalHandler();
    _insertOverlay(callerName);
  }

  void hideIncomingCall() {
    _unregisterGlobalHandler();
    _removeOverlay();
    _storedAnswer = null;
    _storedReject = null;
    _activeRoom = null;
  }

  void _insertOverlay(String callerName) {
    _removeOverlay();

    final overlayState = appNavigatorKey.currentState?.overlay;
    if (overlayState == null) return;

    _overlayEntry = OverlayEntry(
      builder: (_) => _IncomingCallOverlay(
        callerName: callerName,
        onAccept: _acceptCall,
        onReject: _rejectCall,
      ),
    );
    overlayState.insert(_overlayEntry!);
  }

  void _removeOverlay() {
    _overlayEntry?.remove();
    _overlayEntry = null;
  }

  void _registerGlobalHandler() {
    if (_dispatcherToken != null) return;
    _dispatcherToken =
        VivokaCommandDispatcher.I.setGlobalFallback(_onGlobalCommand);
  }

  void _unregisterGlobalHandler() {
    if (_dispatcherToken == null) return;
    VivokaCommandDispatcher.I.setGlobalFallback(null);
    _dispatcherToken = null;
  }

  void _onGlobalCommand(String cmd) {
    switch (cmd) {
      case 'accept call':
      case 'start call':
        _acceptCall();
      case 'reject call':
      case 'end call':
      case 'hang up':
        _rejectCall();
    }
  }

  void _acceptCall() {
    _storedAnswer?.call();
    final room = _activeRoom;
    hideIncomingCall();
    if (room != null) {
      appNavigatorKey.currentState
          ?.pushNamed(CallScreen.route, arguments: room);
    }
  }

  void _rejectCall() {
    _storedReject?.call();
    hideIncomingCall();
  }
}

class _IncomingCallOverlay extends StatelessWidget {
  final String callerName;
  final VoidCallback onAccept;
  final VoidCallback onReject;

  const _IncomingCallOverlay({
    required this.callerName,
    required this.onAccept,
    required this.onReject,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.black87,
      child: SafeArea(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.call_received, size: 64, color: Colors.white70),
            const SizedBox(height: 16),
            const Text(
              'Incoming Call',
              style: TextStyle(color: Colors.white70, fontSize: 16),
            ),
            const SizedBox(height: 8),
            Text(
              callerName,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 26,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 56),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                _OverlayButton(
                  icon: Icons.call_end,
                  color: Colors.red,
                  label: 'Reject',
                  onTap: onReject,
                ),
                _OverlayButton(
                  icon: Icons.call,
                  color: Colors.green,
                  label: 'Accept',
                  onTap: onAccept,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _OverlayButton extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String label;
  final VoidCallback onTap;

  const _OverlayButton({
    required this.icon,
    required this.color,
    required this.label,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          CircleAvatar(
            radius: 32,
            backgroundColor: color,
            child: Icon(icon, color: Colors.white, size: 32),
          ),
          const SizedBox(height: 8),
          Text(label, style: const TextStyle(color: Colors.white, fontSize: 14)),
        ],
      ),
    );
  }
}
