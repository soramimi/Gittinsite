# ワークスペースのルートで、ビルド、エミュレーター起動、デプロイを個別に実行する。
#   make build
#   make emulator
#   make deploy SERIAL=emulator-5554

# デプロイ先は、adb devices -l の一覧に表示されるシリアル番号で指定する。
# ?= は SERIAL が未定義の場合だけ設定する。既定値は USB 接続の実機の番号。
# 別の端末を使う場合は make deploy SERIAL=端末のシリアル番号 で上書きできる。
# SERIAL= と空にすると、deploy.sh の既定値である emulator-5554 が使われる。
SERIAL ?= K5AXB76157379AL

# 同名のファイルやディレクトリがあっても、指定したレシピを毎回実行する。
.PHONY: build emulator deploy

# 引数なしの make では、最初の通常ターゲットである build が選ばれる。
# Gradle Wrapper でデバッグ APK を生成する。
build:
	./app/scripts/build.sh

# エミュレーターをフォアグラウンドで起動する。起動中にデプロイできるよう、
# このターゲットは別のターミナルで実行する。
# 仮想端末を切り替える場合は make emulator AVD_NAME=仮想端末名 を使う。
emulator:
	./app/scripts/run-emulator.sh

# ビルド済みの APK を使うため、ソース変更後は先に make build を実行する。
# 実機なら USB デバッグを許可し、エミュレーターなら Android の起動を待つ。
deploy:
	./app/scripts/deploy.sh "$(SERIAL)"
