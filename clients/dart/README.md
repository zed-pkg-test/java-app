# zed_pkg_client (Dart)

Dart SDK for the [zed-pkg](https://zpkg.tech) registry. Models mirror the
JSON Schemas in `zed-interfaces/schemas/`.

```dart
import 'package:zed_pkg_client/zed_pkg_client.dart';

final client = ZedClient(); // https://registry.zpkg.tech
final pkg = await client.getPackage('acme', 'kit');
final version = await client.getVersion('acme', 'kit', pkg.latest!);
final bytes = await client.downloadArtifact(version); // sha256-verified
```

Authenticated calls (`claimOrg`, `yank`, `publish`) need a bearer token:

```dart
final authed = ZedClient(token: 'zpkg_...');
await authed.claimOrg('acme');
await authed.publish(publishMeta, artifactBytes);
```

Verify with `dart pub get && dart analyze && dart test`.
