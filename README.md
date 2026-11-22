# Gittinsite

Gittinsite は、Android の共有フォルダと GitHub リポジトリを双方向に同期する Kotlin アプリです。最初の用途は Obsidian Vault の同期です。既存ファイルの保全を最優先にします。詳細は [実装計画](PLAN.md) を参照してください。

Gittinsite の起動画面では、リポジトリを登録して個別の Git 操作を実行できます。アプリ ID は `jp.soramimi.gittinsite` で、対応 OS は Android 11（API 30）以上です。

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

旧アプリ ID の開発版を導入済みの場合、この APK は端末上で別のアプリとしてインストールされます。新しい Gittinsite で共有ストレージへのアクセスを許可し、使っていた共有フォルダを既存リポジトリとして登録してください。

## リポジトリの登録と手動操作

1. **Gittinsite** を開き、**アクセスを許可** から共有ストレージへのアクセスを許可します。
2. **リポジトリを追加** で、既存フォルダの登録か新規 clone を選びます。**参照** で共有ストレージ内をたどり、**このフォルダを選択** を押すと絶対パスが入力されます。新しい clone 先を指定する場合は、親フォルダを選んでから入力欄にフォルダ名を追記できます。GitHub HTTPS URL、ブランチ、コミット作成者を設定します。既存フォルダを登録する場合は、現在のブランチと `origin` を入力内容に合わせてください。
3. 詳細画面の **status** で変更を確認し、対象ファイルを選んで **add** を押します。**commit** でメッセージを指定し、**push** で対象ブランチを確認して送信します。
4. リモートの変更を取り込む場合は、**fetch** の後に **fast-forward** を押します。追跡済みの未コミット変更や取り込み先と重なるファイルがあれば、理由が表示されます。
5. 未コミットの編集を破棄する場合は、詳細画面の **変更を破棄（reset）** で対象を確認します。実行すると、追跡済み・ステージ済みの変更が現在の `HEAD` に戻ります。ステージした新規ファイルも破棄対象です。未追跡ファイルは保持され、復元先と重なる場合は処理を中止します。

登録情報は DataStore に保存されます。PAT は端末の入力欄に入れ、push やプライベートリポジトリの通信時に使います。登録解除では Android 上の作業フォルダはそのまま使えます。

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
