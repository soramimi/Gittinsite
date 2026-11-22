#!/usr/bin/env bash

# Gradle Wrapper で app モジュールのデバッグ APK をビルドする。
# 出力先は Gradle プロジェクトのルートから見て app/build/outputs/apk/debug/app-debug.apk。
# 依存関係が取得済みなら、Gradle に --offline を渡してビルドできる。
#   ./app/scripts/build.sh --offline

# コマンドの失敗や未定義変数の参照で停止する。パイプラインを使う場合も、
# 途中のコマンドの失敗を終了ステータスに反映する。
set -euo pipefail

# スクリプトがあるディレクトリの一つ上に、Gradle Wrapper がある。
# その場所を絶対パスで求め、呼び出し元の作業ディレクトリに依存しないようにする。
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)"

# 既に設定されている環境変数を優先する。未設定または空の場合は、
# この環境にある JDK 25 と Android SDK のパスを使う。
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

# Gradle Wrapper のある場所に移動し、追加の引数をそのまま渡す。
# exec でシェルをビルドプロセスに置き換え、その終了ステータスを呼び出し元に返す。
cd -- "$project_dir"
exec ./gradlew :app:assembleDebug "$@"
