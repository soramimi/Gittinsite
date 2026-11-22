# Gittinsite 実装計画

## 1. 目的

Gittinsiteは、Android上でユーザーが指定した、アプリから読み書きできる共有フォルダをGitHubのリポジトリと双方向同期する、Obsidian非依存のGit同期アプリ。

初期用途はObsidian Vault（Markdownファイル群）の同期。Obsidianプラグインを使用せず、独立したAndroidアプリとして動作することを目標とする。

**最優先要件:** 既存ファイルを失わないこと。同期失敗・競合時に自動で破壊的なリセットや上書きを実行しない。

## 2. 初期スコープ（MVP）

### 対応する機能

- リポジトリ（ローカルの作業ディレクトリ）の登録・表示・解除（解除は登録情報のみ削除）
- GitHub HTTPS URLとブランチの設定
- 初回 `clone`（空の保存先ディレクトリに限定）
- 既存Gitリポジトリの登録（`.git`の存在・有効性を検証）
- `status`、変更のステージング（`add`）、`commit`、`fetch`、fast-forwardを基本とする取り込み、`push`
- 手動の「同期」ボタン、処理結果とエラーの表示
- GitHub認証（HTTPS、Personal Access Token）
- 操作中の進捗表示・キャンセル、リポジトリ単位での多重実行防止

### MVPでは実装しないもの

- 自動同期・バックグラウンド定期実行
- 複雑な競合解決UI、履歴ブラウザ、rebase UI
- SSH認証、Git LFS、submoduleの高度な管理
- SAF（Storage Access Framework）で選択したフォルダへの直接Git操作
- 非Gitの既存Vaultの初期化・移行（M3で導入フローを整備）
- 複数GitHubアカウント、OAuthログイン
- Google Play公開への最適化

## 3. 技術スタック

| 項目 | 方針 |
| --- | --- |
| Androidアプリ | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Gitエンジン | JGit（最初にAndroid互換性を実機検証） |
| 非同期処理 | Kotlin Coroutines + `Dispatchers.IO` |
| 状態管理 | ViewModel + StateFlow |
| 永続設定 | DataStore（秘密情報以外） |
| 認証情報 | Android Keystoreで保護した暗号化データ |
| 通信 | JGitのHTTPS転送 |
| 初期対応OS | MVPの `minSdk` はAndroid 11（API 30）。`targetSdk` は導入時に決定 |
| 配布 | 最初は個人用APK（サイドロード） |

### JGitの導入方針

1. 公式JGitの特定バージョンを固定し、Android実機で初期化、clone、fetch、commit、pushを試験する。
2. Javaランタイム/API、暗号/TLS、依存ライブラリ、リソース読み込みなどで互換性問題が出た場合は、Android向けフォークを調査する。
3. フォークを採用する場合、更新状況、差分、ライセンス、セキュリティ修正の追従性を確認する。
4. 互換性検証を通過するまで本格的なUI実装には進まない。

参考: https://projects.eclipse.org/projects/technology.jgit 、 https://github.com/Gazman-Dev/jgit

## 4. ストレージ設計

MVPでは、ユーザーが明示的に指定した**共有ストレージ内の通常のファイルパス**をJGitで操作する。

- Android 11以降では `MANAGE_EXTERNAL_STORAGE` を使用する方針。
- `Environment.isExternalStorageManager()` で権限を検査し、必要時に設定画面へ案内する。
- `/Android/data` など、他アプリの専用領域はアクセスできない。対象フォルダが実際に読み書きできるか都度検証する。
- SAFのツリーURIを通常の`java.io.File`として扱わない。
- 権限取得済みでも、選択パスが存在しない・読み込み専用・容量不足などのケースを適切に扱う。
- ディレクトリ内のシンボリックリンク、Git管理ファイル、ファイル名の大文字小文字、ファイル更新日時・パーミッションの挙動を実機で検証する。
- 登録先の実体パスを検証する。同じフォルダの重複登録と、他の登録先を含む・含まれるパスの登録を拒否する。

**重要:** `MANAGE_EXTERNAL_STORAGE` は高リスク権限であり、Google Play公開には厳しい審査がある。個人利用MVPで採用し、公開を検討する段階でSAFや別のストレージ設計を再評価する。

## 5. データモデル（案）

```text
RepositoryConfig
  id: UUID
  name: String
  workingDirectory: String
  remoteName: String = "origin"
  remoteUrl: String
  branch: String
  autoCommitMessage: String = "Sync from Android"
  lastSyncAt: Instant?
  lastResult: SyncResult?
  credentialId: String?
```

- リポジトリごとに設定を保存し、秘密情報は分離する。
- `lastSyncAt` は成功時のみ更新する。
- リモートURLにトークンを埋め込まず、通信時の認証コールバックで渡す。
- 将来の他サービス対応のため、GitHub固有の処理をGitエンジンから分離する。

## 6. 構成（案）

```text
app/
  ui/
    RepositoryListScreen
    RepositoryDetailScreen
    RepositorySetupScreen
    SettingsScreen
  viewmodel/
    RepositoryViewModel
    SyncViewModel
  domain/
    RepositoryConfig
    SyncState
    SyncResult
    SyncCoordinator
  git/
    GitRepositoryService
    JGitRepositoryService
    GitCredentialProvider
  storage/
    RepositoryConfigStore
    CredentialStore
    StorageAccessChecker
  logging/
    SyncLog
```

`GitRepositoryService` をインターフェイスとし、UIや同期フローがJGit APIに直接依存しないようにする。これにより将来libgit2などへの切り替えやユニットテストが容易になる。

## 7. 同期アルゴリズム

### 手動同期

1. 作業ディレクトリの実体パスをキーにロックを取得する。M1の手動操作にも同じ排他制御と、操作に応じた事前検査を適用する。
2. ストレージ権限、パス、`.git`、HEAD、fetch先とpush先、ブランチ、認証設定を検査する。detached HEADや、設定したブランチと現在のブランチが異なる場合は停止する。
3. 既存のマージ・rebase・競合などの未完了状態や、同期開始前からのステージ済み変更を検出したら**停止**する。
4. 作業ツリーの変更を確認し、ユーザー設定の対象ファイルを `add` / `add --update` 相当でステージングする。対象に含まれる未追跡ファイルと削除を扱い、`.gitignore`を尊重する。
5. 変更があればローカルcommitを作成する。変更がなければcommitは行わない。
6. 設定済みremoteから対象ブランチをfetchする。fetchに失敗した場合やリモートブランチが存在しない場合は停止し、ローカルcommitを保持する。以前の追跡refで判定を続けない。
7. ローカルとリモートのコミット関係を判定する。
   - 同一: 追加操作なし
   - ローカルのみ進行: push可能
   - リモートのみ進行: HEAD、ブランチ、インデックス、作業ツリーを再確認してからfast-forwardする。変更を検出した場合や、取り込みで未追跡・無視対象のファイルを上書きする場合は停止する。
   - 両方進行、または共通祖先が確認できない: 停止して理由を表示
8. ローカルのみ進行している場合に限り、リポジトリが正常状態であることと対象ブランチを再確認し、送信元・送信先のブランチを明示してpushする。non-fast-forward拒否はエラーとして明示する。
9. 結果を保存し、成功時のみ最終成功日時を更新する。失敗やキャンセル時も、実行中のGit処理が終了してからロックを解放する。

### 分岐・競合方針

- **MVPのデフォルト:** 両方に独立したcommitが存在した場合は自動マージせず停止する。GitHub側とローカル側の変更は保持される。
- これでは日常的な双方向同期が分岐で停止しやすいため、次段階で通常の3-way merge（競合なしなら自動マージ）を追加する。
- マージで競合が発生した場合はpushしない。競合状態と対象ファイルを表示し、解決手段が実装されるまで自動的な上書き・resetをしない。
- `force push`、`reset --hard`、無条件のcheckout、既存ディレクトリの削除を同期処理で行わない。
- リポジトリ詳細の手動resetでは、対象ファイルを確認してから作業ツリーとインデックスを現在のHEADに戻す。対象の内容が確認後に変わった場合や、復元先が未追跡・無視対象ファイルと重なる場合は中止する。
- 途中で通信切断されても、ローカルcommitを保持し、再同期可能な状態にする。
- pushの応答前に通信が切れた場合は結果不明と表示し、再試行時のfetchでリモートへの反映を確認する。
- キャンセルを受け付けたら後続の操作を開始しない。中断できない処理は終了を待ち、完了済みのcommitなどは保持する。途中でステージ済みの変更が残った場合は通知し、内容を確認するまで次回の同期を停止する。
- アプリ内のロックでObsidianや他のGitクライアントの書き込みは止められない。MVPの運用では取り込み中のVault編集を避ける。作業ツリーの再検査に加え、外部書き込みが発生した場合の挙動を実機で確認する。

### 既存Vaultの初期登録

**既存の非空フォルダに `clone` を直接実行しない。** 初期導入は以下の2経路に分ける。

- **新規Vault:** 空の保存先にcloneし、ObsidianからそのフォルダをVaultとして開く。
- **既存Vault:** まずファイルをバックアップする。既存フォルダにGitリポジトリがあれば登録する。非GitのVaultは、MVPでは手動移行を案内する。M3では別導線での初期化・移行を検討し、既存リモートの履歴との自動統合は行わない。

Obsidianの設定ファイルや端末固有ファイルが意図せず共有されないよう、最初に`.gitignore`を確認する。すでに追跡中のファイルは`.gitignore`に書くだけでは除外されないことも案内する。

## 8. 認証・セキュリティ

- GitHubの**Fine-grained Personal Access Token** を初期認証方式とする。対象リポジトリに必要な最小限のContents権限を付与する。
- `UsernamePasswordCredentialsProvider` 等のJGit機構へ必要な時だけ認証情報を供給する。
- 登録時と通信直前に、fetchとpushそれぞれの実際の接続先が、登録した同じGitHubリポジトリのHTTPS URLを指すことを検証する。PATは検証済みの接続先にのみ供給し、接続先を変更するリダイレクトには引き継がない。
- トークンはDataStore、ログ、Git設定、リモートURLに平文で保存しない。
- Android Keystore内の非エクスポート鍵で暗号化し、アプリ専用ストレージに暗号文を保存する。
- TLS証明書の検証を無効にしない。
- ログのURL・例外・HTTPヘッダー・メッセージからトークンを除去する。
- 取り扱い可能なリポジトリサイズ、ファイル数、空き容量を検査する。
- 認証失敗、権限不足、通信切断、GitHubの拒否を区別して表示する。
- 暗号文をバックアップと端末間移行の対象から除外する。復元後など鍵または暗号文が利用できない場合は、PATの再入力を案内する。

## 9. UI（MVP）

### リポジトリ一覧

- 登録済みリポジトリ名、パス、ブランチ、最終成功日時、最後の結果
- 「リポジトリ追加」「同期」ボタン

### リポジトリ登録

- 新規clone / 既存リポジトリ登録を選択
- ローカルパス、リモートURL、ブランチ、認証情報
- 空/非空の保存先、権限、remoteの整合性を検証

### 同期詳細

- 現在の段階（確認、commit、fetch、統合、push）、進捗、キャンセル
- 最終結果（成功、変更なし、分岐、競合、認証失敗、通信失敗など）
- 簡易ログ（秘密情報はマスク）

## 10. 実装マイルストーン

### M0: 実機での技術検証（完了）

既存のKotlinプロジェクトを土台にする。共有ストレージ経路の検証にはAndroid 11（API 30）以上が必要で、検証機としてSamsung SM-M236Q（Android 14、API 34）を使用する。

- [x] 既存のKotlinプロジェクトで検証用ビルドを用意
- [x] Android 11以上の検証機を用意し、OS・APIレベルを記録
- [x] JGitのバージョン/依存を固定し、Android実機でロード可能か確認
- [x] アプリ専用領域でローカルGit操作を試験
- [x] メモリ内だけで扱うテスト用PATで、テストリポジトリへのHTTPS clone/fetch/pushを試験
- [x] Android 11以上の実機で共有ストレージへの読み書きと`.git`作成を検証
- [x] 共有ストレージ上のVaultをObsidianで編集し、同期後の反映を検証

**完了条件:** Android 11以上の実機で、共有フォルダへのテストVaultのclone → Obsidianでの編集 → アプリからcommit・push → PCからの取得まで成功する。PCで別の変更をpushし、アプリからfetch・fast-forwardした内容をObsidianで確認できる。

**検証記録:** JGit 7.8.1はAPI 30で `InputStream.readNBytes(int)` を呼び出せなかった。公式のJGit 5.13.5（`5.13.5.202508271544-r`）に切り替え、API 30とAPI 37のエミュレーターで、アプリ専用領域と共有Documentsフォルダの両方に対するローカルGitの往復試験が通過した。Android 9（API 28）のASUS実機でもアプリ専用領域の往復試験が通過した。

Samsung SM-M236Q（Android 14、API 34）では、アプリ専用領域と共有Documentsフォルダの両方でローカルGitの往復試験が通過した。共有領域の試験では、一時リポジトリの作成、clone、commit、push、fetch、fast-forward、ファイル内容の一致を確認した。

同じSamsung実機で、PCからpushした変更をfetch・fast-forwardし、Obsidianに反映されたことを利用者が確認した。端末のfast-forward先はPCの検証用チェックアウトのHEADと一致した。続くObsidianでの編集では、未追跡の設定ファイルが原因でcommitが停止したため、手動ステージを追加した。更新後、利用者が端末でステージ、commit、pushを実行し、GitHubで反映を確認した。PCの一時リポジトリにGitHubから取得したコミットも、端末で作成された最新の検証コミットと一致した。

### M1: リポジトリ登録と手動コマンド

- [x] 既存のKotlinプロジェクトにComposeとMaterial 3を導入
- [x] 権限要求・検査画面
- [x] `RepositoryConfig` とDataStoreによる保存
- [x] 新規clone、既存リポジトリ登録
- [x] status / add / commit / fetch / fast-forward / push を画面に実装
- [x] 実体パス単位の排他制御と、手動操作ごとの事前検査
- [x] エラー分類、基本ログ、秘密情報を含まない結果表示
- [x] PATはメモリ内だけで扱う。将来保存する段階でKeystore保護、バックアップ除外、再入力の導線を実装する。
- [ ] 通常の起動画面からテストリポジトリにPATでpushし、GitHub側で反映を確認

**完了条件:** UIから各Git操作が実行でき、結果を判別できる。

**検証記録:** Android 14実機にM1をデプロイし、一時リポジトリで登録・保存・解除、status・選択add・commit、公開GitHubリポジトリからのclone・fetch・fast-forwardを確認した。追跡済みのローカル編集がある場合はfast-forwardを中止し、ファイルの内容が保たれた。エミュレーターでComposeの一覧表示と登録フォームへの遷移を確認した。PAT付きpushの実機試験はM0の検証画面で確認済みで、M1の通常画面からの試験が残っている。

### M2: 安全なワンタップ同期

- [ ] `SyncCoordinator` を実装
- [ ] 同期の開始から終了まで、M1で導入したリポジトリ単位のロックを保持
- [ ] 正常・分岐・競合・通信障害の分岐処理
- [ ] cancelと再試行、UIの進捗表示

**完了条件:** 変更なし／片側変更のみのケースでワンタップ同期でき、両側変更時はファイルを失わず停止する。

### M3: 実用的な双方向同期

- [ ] 競合のない3-way mergeを自動実行
- [ ] 競合ファイルと解決方針を表示
- [ ] 既存Vaultの導入フローを整備
- [ ] Androidの共有ストレージ固有の問題を試験

**完了条件:** AndroidとPCの両方で異なるファイルを編集しても、安全に同期できる。

### M4: 汎用化・自動化（将来）

- [ ] 複数リポジトリの設定と一括同期
- [ ] WorkManagerで定期同期（Androidの実行タイミング制約を許容）
- [ ] Wi-Fi時のみ、充電時のみ等の条件
- [ ] SSH認証、複数アカウント
- [ ] SAF対応方式・Play配布方針の再検討

## 11. テスト計画

### 単体テスト（可能な範囲で一時ディレクトリとローカルbare repositoryを使用）

- [ ] 変更なしで同期（不要なcommitを作成しない）
- [ ] ローカル追加・修正・削除のcommit
- [ ] リモートだけ更新：fast-forward
- [ ] ローカルだけ更新：push
- [ ] 同一ファイルを別端末で編集：分岐検出・停止
- [ ] 別ファイルを別端末で編集：M3で自動マージ
- [ ] 未追跡ファイル、`.gitignore`、ドットファイル
- [ ] 既存の未完了merge/rebaseの検出
- [ ] 既存のステージ済み変更、detached HEAD、ブランチ不一致、リモートブランチ消失で停止
- [ ] fast-forward時に未追跡・無視対象ファイルとの衝突を検出して停止
- [ ] 非Gitフォルダ、空でないclone先、誤ったremote
- [ ] push拒否、fetch失敗、認証切れ、通信切断
- [ ] push応答前の切断と、次回fetchでの反映確認
- [ ] 同時同期の二重実行防止、キャンセル後の復帰

### Android実機テスト

- [ ] 共有ストレージ上の`.git`の作成と読み取り
- [ ] Obsidian起動中の同期とファイル更新の反映、取り込み直前の再検査までに外部編集があった場合の停止と内容の保持
- [ ] 日本語ファイル名・Unicode・長いパス
- [ ] 大量の小ファイル、数MB以上の添付ファイル
- [ ] 空き容量不足、途中での権限取り消し
- [ ] アプリ終了/強制終了、通信中断、端末再起動
- [ ] GitHubからpullしたファイルがObsidianで見えること
- [ ] GitHubにpushした変更がPCのGitでも取得できること

**実運用開始前:** テスト用Vaultとテスト用GitHubリポジトリで少なくとも追加・変更・削除・競合・通信切断を確認する。重要データのバックアップを取る。

## 12. 保留する設計判断

1. JGit公式版のAndroid互換性と採用バージョン（M0で確定）。
2. 既存の非Git VaultをGitHubの既存履歴に接続する移行UX。
3. 分岐時にmerge commitを使うか、rebaseを採用するか（MVPは自動統合しない）。
4. 自動commitのメッセージと対象ファイルの選択方法。
5. AndroidのtargetSdk、Google Play公開可否、SAF対応方法。
6. Obsidianが開いているファイルの書き換えと、同時編集時の整合性ポリシー。

## 13. 参考資料

- JGit: https://projects.eclipse.org/projects/technology.jgit
- Android向けJGitフォーク（要評価）: https://github.com/Gazman-Dev/jgit
- Android「すべてのファイルへのアクセス」: https://developer.android.com/training/data-storage/manage-all-files
- Google Play権限ポリシー: https://support.google.com/googleplay/android-developer/answer/10467955
- GitHub Fine-grained PAT: https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens
