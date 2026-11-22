#!/usr/bin/env bash

# ビルド済みのデバッグ APK を、指定した実機またはエミュレーターに
# インストールして MainActivity を起動する。
# 接続先のシリアル番号は、SDK の platform-tools にある adb で確認できる。
#   "$HOME/Android/Sdk/platform-tools/adb" devices -l
#   ./app/scripts/deploy.sh emulator-5554

# コマンドが失敗した時点で停止する。インストールに失敗した場合、起動には進まない。
# 未定義変数の参照や、パイプラインの途中で起きた失敗も検出する。
set -euo pipefail

# スクリプトの配置場所を起点に、ビルド済み APK の絶対パスを求める。
# adb は指定された SDK の platform-tools から直接呼び出す。
# 第1引数が未指定または空の場合は emulator-5554 を使う。
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
adb="$ANDROID_HOME/platform-tools/adb"
serial="${1:-emulator-5554}"
apk="$project_dir/app/build/outputs/apk/debug/app-debug.apk"

# 引数は接続先のシリアル番号1つまで受け付ける。
if (( $# > 1 )); then
    printf 'Usage: %s [device-serial]\n' "${0##*/}" >&2
    exit 2
fi

# APK がまだ作られていない場合は、先に実行するビルドスクリプトを案内する。
if [[ ! -f "$apk" ]]; then
    printf 'APK not found. Run %s first.\n' "$project_dir/scripts/build.sh" >&2
    exit 1
fi

# -s で対象端末を指定する。-r はアプリデータを保持したまま上書きし、
# -t は android:testOnly が付いた APK のインストールを許可する。
"$adb" -s "$serial" install -r -t "$apk"

# インストールに成功した端末で、アプリ ID と Activity の完全修飾名を指定して起動する。
# -W を付けると、起動処理の結果が返るまで待機する。
exec "$adb" -s "$serial" shell am start -W -n jp.soramimi.gittinsite/com.example.myapplication.MainActivity
