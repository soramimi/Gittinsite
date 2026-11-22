#!/usr/bin/env bash

# Android SDK の emulator コマンドで、作成済みの仮想端末（AVD）を起動する。
#   ./app/scripts/run-emulator.sh
#   AVD_NAME=Medium_Phone_API_37.0 ./app/scripts/run-emulator.sh -no-window

# エラーや未定義変数の参照で停止する。パイプラインの途中の失敗も検出する。
set -euo pipefail

# SDK の場所と仮想端末名は環境変数で上書きできる。未設定または空の場合は、
# この環境にある SDK と、作成済みの仮想端末を使う。
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
avd_name="${AVD_NAME:-Medium_Phone_API_37.0}"

# -avd で起動対象を指定する。追加の引数はそのまま渡すため、
# -no-window を指定するとウィンドウを表示せずに起動できる。
# exec でシェルをエミュレーターに置き換え、フォアグラウンドで実行する。
exec "$ANDROID_HOME/emulator/emulator" -avd "$avd_name" "$@"
