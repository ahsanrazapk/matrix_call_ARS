import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:matrix/matrix.dart';
import 'package:matrix_call/voip/barcode_scanner.dart';
import 'package:matrix_call/voip/login_screen.dart';
import 'package:matrix_call/voip/scan_to_proced.dart';
import 'package:matrix_call/voip/vivoka_sdk.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:provider/provider.dart';
import 'package:sqflite/sqflite.dart' as sqlite;
import 'package:name_avatar/name_avatar.dart';
import 'package:flutter_vodozemac/flutter_vodozemac.dart' as vod;


import 'voip/voip_service.dart';
import 'voip/call_screen.dart';
final GlobalKey<NavigatorState> appNavigatorKey = GlobalKey<NavigatorState>();
final RouteObserver<ModalRoute<void>> routeObserver = RouteObserver<ModalRoute<void>>();



void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  await vod.init(wasmPath: './assets/assets/vodozemac/');


  final dir = await getApplicationSupportDirectory();
  final dbPath = '${dir.path}/matrix.sqlite';

  final client = Client(
    'Matrix VoIP Example',
    database: await MatrixSdkDatabase.init(
      'matrix_voip_example',
      database: await sqlite.openDatabase(dbPath),
    ),
      nativeImplementations: NativeImplementationsIsolate(
        compute,
        vodozemacInit: () => vod.init(wasmPath: './assets/assets/vodozemac/'),
      ),
    defaultNetworkRequestTimeout: const Duration(minutes: 30),
    enableDehydratedDevices: true,
    shareKeysWith: ShareKeysWith.all,
    convertLinebreaksInFormatting: false,
    onSoftLogout: (client) => client.refreshAccessToken(),
  );

  await Permission.microphone.request();
  await VivokaSdkFlutter.init();
  await client.init();

  runApp(
    MultiProvider(
      providers: [
        Provider<Client>.value(value: client),
        ChangeNotifierProvider(create: (_) => VoipService(client)),
      ],
      child: const MyApp(),
    ),
  );
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {

    return  MaterialApp(
      title: 'Matrix Call',
      navigatorKey: appNavigatorKey,
      initialRoute: LoginPage.route,
      onGenerateRoute: (settings) {
        switch (settings.name) {
          case CallScreen.route:
            final room = settings.arguments;
            if (room is! Room) return null;
            return MaterialPageRoute(
              settings: settings,
              builder: (_) => CallScreen(room: room),
            );

          case LoginPage.route:
            return MaterialPageRoute(
              settings: settings,
              builder: (_) => const LoginPage(),
            );

          case ScanToProceedScreen.route:
            return MaterialPageRoute(
              settings: settings,
              builder: (_) => const ScanToProceedScreen(),
            );

          case BarcodeScanner.route:
            return MaterialPageRoute<String?>(
              settings: settings,
              builder: (_) => const BarcodeScanner(),
            );

          default:
            return null;
        }
      },
      navigatorObservers: [routeObserver],
    );

  }
}


/*class RoomListPage extends StatefulWidget {
  const RoomListPage({super.key});
  @override
  State<RoomListPage> createState() => _RoomListPageState();
}

class _RoomListPageState extends State<RoomListPage> {
  Future<void> _logout() async {
    final client = context.read<Client>();
    await context.read<VoipService>().stop();
    await client.logout();

    if (!mounted) return;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const LoginPage()),
          (_) => false,
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    SchedulerBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      await context.read<VoipService>().start();
      VivokaSdkFlutter.events().listen((e) {
        if (e.type == 'command') {
          print("COMMAND: ${e.text}");
        }
      });


    });
  }

  Future<void> _startVoiceCall(Room room) async {
    final other = room.directChatMatrixID;
    final nav = appNavigatorKey.currentState;
    if (!mounted) return;
    await context.read<VoipService>().callUser(
      roomId: room.id,
      userId: other ?? '',
      type: CallType.kVoice,
    );

    if (!mounted) return;

    if (nav == null) return;
    final currentName = ModalRoute.of(nav.context)?.settings.name;
    if (currentName != CallScreen.route) {
      nav.pushNamed(CallScreen.route);
    }
  }

  Future<void> _startVideoCall(Room room) async {
    final other = room.directChatMatrixID;
    print(other);
    final nav = appNavigatorKey.currentState;
    if (!mounted) return;
    await context.read<VoipService>().callUser(
      roomId: room.id,
      userId: other ?? '',
      type: CallType.kVideo,
    );

    if (!mounted) return;
    if (nav == null) return;
    final currentName = ModalRoute.of(nav.context)?.settings.name;
    if (currentName != CallScreen.route) {
      nav.pushNamed(CallScreen.route);
    }
  }

  @override
  Widget build(BuildContext context) {
    final client = context.read<Client>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Rooms'),
        actions: [
          IconButton(onPressed: _logout, icon: const Icon(Icons.logout,color: Colors.grey,)),
        ],
      ),
      body: StreamBuilder(
        stream: client.onSync.stream,
        builder: (_, _) {
          final rooms = client.rooms;
          return ListView.separated(
            itemCount: rooms.length,
            itemBuilder: (_, i) {
              final r = rooms[i];
              return ListTile(
                horizontalTitleGap: 10,
                leading: NameAvatar(
                  name: r.getLocalizedDisplayname(),
                  isTwoChar: false,
                  radius: 25,
                  backgroundColor: Colors.grey,
                  textStyle: TextStyle(fontSize: 16, fontWeight: FontWeight.w500,color: Colors.white),
                ),
                title: Text(r.getLocalizedDisplayname()),
                trailing: Row(mainAxisSize: MainAxisSize.min,children:  [
                  IconButton(
                    icon: const Icon(Icons.call, color: Colors.grey,),
                    onPressed:()=> _startVoiceCall(r),
                  ),
                  IconButton(
                    icon: const Icon(Icons.videocam,color: Colors.grey,),
                    onPressed: ()=>_startVideoCall(r),
                  ),
                ]),
              );
            }, separatorBuilder: (BuildContext context, int index) => Divider(indent: 20,endIndent: 20,thickness: 0.5,height: 30,),
          );
        },
      ),
    );
  }
}*/


