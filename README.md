# Android Git 同期アプリ

Android の共有フォルダと GitHub リポジトリを双方向に同期する Kotlin アプリの開発プロジェクトです。最初の用途は Obsidian Vault の同期です。既存ファイルの保全を最優先にします。詳細は [実装計画](PLAN.md) を参照してください。

現在のアプリは画面中央に `Hello, world` を表示します。Git 同期は実装計画の段階です。現行ビルドの `minSdk` は 24 で、共有ストレージを使う MVP は Android 11（API 30）以上を対象にします。

## 開発環境

Linux での手順です。コマンドはリポジトリのルートから実行します。

- Bash、Make、JDK 25
- Android SDK Platform 37、SDK Build-Tools、SDK Platform-Tools
- 仮想端末で試す場合は Android Emulator、システムイメージ、作成済みの AVD

Gradle は [プロジェクト付属の Wrapper](app/gradlew) が取得します。初回は SDK と依存関係の取得にネット接続が必要です。

ビルドスクリプトは `JAVA_HOME` が未設定なら `/usr/lib/jvm/java-25-openjdk` を使います。JDK 25 を別の場所に入れた場合や `JAVA_HOME` が別の JDK を指す場合は、JDK 25 のパスを設定してください。各スクリプトが使う SDK のパスは既定で `$HOME/Android/Sdk` です。別の場所に入れた場合は `ANDROID_HOME` を設定し、`app/local.properties` に `sdk.dir` があれば、そちらも同じパスに合わせてください。

## ビルド

```bash
make build
```

デバッグ APK はリポジトリのルートから見て `app/app/build/outputs/apk/debug/app-debug.apk` に生成されます。

## エミュレーターで実行

ビルド後、別のターミナルからエミュレーターを起動します。既定の AVD は `Medium_Phone_API_37.0` です。

```bash
make emulator
```

別の AVD を使う場合は、`./app/scripts/run-emulator.sh -list-avds` で名前を調べ、`make emulator AVD_NAME=仮想端末名` で指定します。

Android のホーム画面が表示されたら、元のターミナルで端末一覧を確認してからデプロイします。`emulator-5554` は一覧の左端に表示された値に合わせてください。

```bash
"${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb" devices -l
make deploy SERIAL=emulator-5554
```

## USB 接続の実機で実行

端末の開発者向けオプションで USB デバッグを有効にし、データ通信対応の USB ケーブルで接続します。端末に表示される USB デバッグの許可を承認します。

```bash
"${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb" devices -l
```

状態が `device` の行の左端がシリアル番号です。以下の `ABC123456` を実際の値に置き換えてください。

```bash
make build && make deploy SERIAL=ABC123456
```

`make deploy` はビルド済みの APK を対象端末にインストールし、アプリを起動します。`SERIAL` を省略した場合は [Makefile](Makefile) に設定されている開発用実機が選ばれます。

## テスト

JDK 25 に `JAVA_HOME` を設定したターミナルで、既存のユニットテストを実行できます。

```bash
cd app
./gradlew :app:testDebugUnitTest
```

## 主なファイル

| パス | 内容 |
| --- | --- |
| [PLAN.md](PLAN.md) | 仕様、同期時の安全条件、マイルストーン、テスト計画 |
| [app/app/src/main/](app/app/src/main/) | Kotlin ソース、マニフェスト、Android リソース |
| [app/scripts/](app/scripts/) | ビルド、エミュレーター起動、デプロイのスクリプト |
| [Makefile](Makefile) | ルートから各スクリプトを呼び出すターゲット |
