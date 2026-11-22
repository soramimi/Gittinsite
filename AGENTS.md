# このリポジトリでの作業

実装・設計に着手する前に [PLAN.md](PLAN.md) の対象マイルストーンと完了条件を確認する。開発環境、ビルド、デプロイの手順は [README.md](README.md) を参照する。

## プロジェクト構成

- Gradle プロジェクトのルートは `app/`。Android アプリのモジュールは `app/app/`。
- アプリのソース、マニフェスト、リソースは `app/app/src/main/`。テストは `app/app/src/test/` と `app/app/src/androidTest/`。
- ルートの `Makefile` は `app/scripts/` のスクリプトを呼び出す。SDK や JDK の既定値、引数、起動対象を変更するときは、README の手順も照合する。
- 現行アプリは Kotlin の Hello World。MVP の最低対応バージョンは Android 11（API 30）で、現行ビルドの `minSdk` は 24。

## 実装方針

- M0 では選定した JGit と HTTPS 認証を先に検証し、Android 11 以上の実機で共有フォルダ上のテスト Vault を使って往復同期を確認する。Compose と Material 3 を使う UI は計画上 M1 から導入する。
- 既存ファイルの保全を最優先にする。clone は空の保存先に限る。MVP ではリモートの更新を fast-forward で取り込む。履歴の分岐、未完了のマージや rebase、同期前からのステージ済み変更を検出したら停止する。同期処理で force push、hard reset、既存フォルダの削除を行わない。
- Git の書き込み処理には計画どおり作業ディレクトリの実体パス単位の排他制御を適用する。実機検証にはテスト用 Vault とテスト用リポジトリを用いる。
- PAT をソース、ログ、Git 設定、リモート URL に含めない。M0 のテスト用 PAT はメモリ内で扱う。永続化する場合は Keystore で保護し、暗号文をバックアップ対象から除外する。
- 端末固有の SDK パスは `app/local.properties`、デプロイ先はコマンドの `SERIAL` で設定できる。作業時に使う設定を、機能変更に必要な差分と混ぜない。

## 変更の確認

- Android のコードを変更したら、ルートから `make build` で影響を確認する。既存のユニットテストは、JDK 25 に `JAVA_HOME` を設定した上で `cd app && ./gradlew :app:testDebugUnitTest` で実行する。
- Bash スクリプトの構文は `bash -n`、Make ターゲットの展開は `make -n` で確認できる。
- デプロイを検証するときは、`adb devices -l` で接続先を確認し、`make deploy SERIAL=端末のシリアル番号` で対象を明示する。
- 実機でしか確認できない挙動は、検証した OS・API レベルと結果を記録する。計画のチェック項目は、完了条件を実際に確認できた時点で更新する。
