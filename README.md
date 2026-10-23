# PrivLink — プライバシーモード内のブラウザへリンクを中継する最小アプリ

vivo OriginOS のプライバシーモード（原子隐私系统）内でリンクをタップすると、
システムがメイン側のデフォルトブラウザ（Chrome）へ転送してしまう問題への対処用アプリです。

受け取った URL を、**同じプライバシーモード内にあるブラウザ（Chrome Dev / Beta など）へ
パッケージ名指定の明示的インテントで直接投げる**ので、システムの
「http(s) をどのアプリで開くか」の判定（＝メイン側へ回す処理）を通りません。

## 受け口（3つ）

| 受け口 | 使い方 | 効く条件 |
|---|---|---|
| 共有シート | リンク長押し →「共有」→「PrivLink で開く」 | ほぼ確実に動く（共有シートはプロファイル内アプリを列挙するため） |
| テキスト選択メニュー | URL 文字列を選択 → ツールバーの「PrivLink で開く」 | 同上 |
| デフォルトブラウザ | アプリ内の「デフォルトブラウザに申請」ボタン | プライバシーモードがプロファイル内のデフォルトブラウザを尊重する場合のみ。尊重しない（強制転送）場合は効かない |

依存ライブラリなし・Java のみ・権限要求なし。

## APK の作り方

### A. GitHub Actions でビルド（Android Studio 不要）

1. GitHub に新しいリポジトリ（Private で可）を作り、このフォルダの中身をそのまま push する。
2. 「Actions」タブ → 「Build APK」ワークフローが自動実行される（初回は数分）。
3. 完了した実行を開き、「Artifacts」の `PrivLink-debug-apk` をダウンロード → 中の `app-debug.apk`。

### B. Android Studio でビルド

1. Android Studio で「Open」→ このフォルダを選択。
2. Build → Build Bundle(s) / APK(s) → Build APK(s)。
3. `app/build/outputs/apk/debug/app-debug.apk` ができる。

### C. コマンドライン（Android SDK と Gradle 8.9 以上がある場合）

```
gradle assembleDebug
```

### D. PC でビルド（Windows・Android Studio なしでも可）

前提: JDK 17 以上と Android SDK（Android Studio を入れていれば同梱）。
SDK の場所を `local.properties` に書く（Android Studio で開けば自動生成される）:

```
sdk.dir=C\\Users\\<ユーザー名>\\AppData\\Local\\Android\\Sdk
```

プロジェクトのフォルダで:

```
gradlew.bat assembleDebug
```

初回は Gradle 8.9 や Android のライブラリを自動ダウンロードするので数分かかる。
`app\build\outputs\apk\debug\app-debug.apk` ができる。

## インストールと配置

1. `app-debug.apk` を端末に転送してインストール（または PC から `adb install app-debug.apk`）。
2. プライバシーモードに入り、ホームの「＋（追加）」から **PrivLink をプライバシーモード側へ移動**する。
   （転送先の Chrome Dev / Beta もプライバシーモード側に入っていること）
3. プライバシーモード内で PrivLink を開き、転送先ブラウザを確認・選択する。
   何も選ばなければ Chrome Dev → Beta → Canary → Chrome の順で自動選択。
4. 「デフォルトブラウザに申請」を押してみる（通れば以後はリンクのタップだけで転送先が開く）。
   通らない／設定してもメイン側 Chrome が開く場合は、「共有」またはテキスト選択メニュー経由で使う。
5. 初回起動時に vivo の「“PrivLink” オープン希望 “Chrome Beta”」ダイアログが出たら「常にオープン」。

## 仕組みの補足（なぜメイン側 Chrome が開くのか）

- プライバシーモードは別の Android ユーザープロファイルとして動いている（アプリ名の「Ⅱ・」はその複製）。
- AOSP 標準の Private Space には http(s) をメイン側へ転送する cross-profile intent filter は無く、
  vivo 独自に「Web リンクはメイン側のデフォルトブラウザで開く」ルートが入っていると考えられる。
- 「“Telegram” オープン希望 “Chrome”」ダイアログは vivo のアプリ間起動確認で、
  「常にオープン／1回オープン」は確認の有無を決めるだけで、行き先（どのブラウザか）は変えない。

## ADB での調査・設定（任意・上級）

PC に adb がある場合、プライバシーモードのユーザー ID と、そのユーザーでの http(s) の解決先を確認できる。

```
adb shell pm list users
adb shell pm resolve-activity --brief --user <ID> -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d https://example.com
adb shell pm query-activities --brief --user <ID> -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d https://example.com
```

解決先が `com.android.internal.app.IntentForwarderActivity` ならメイン側への転送、
`com.chrome.beta/...` ならプロファイル内で解決されている。

プロファイル内のデフォルトブラウザを ADB から直接指定する（元に戻すのも同じ形式）:

```
adb shell cmd role add-role-holder --user <ID> android.app.role.BROWSER com.chrome.beta
adb shell cmd role get-role-holders --user <ID> android.app.role.BROWSER
```

vivo 側が強制転送している場合はこれでも変わらない。その場合は共有／テキスト選択経由を使う。

## パッケージ名メモ

- Chrome Dev: `com.chrome.dev`
- Chrome Beta: `com.chrome.beta`
- Chrome Canary: `com.chrome.canary`
- Chrome: `com.android.chrome`
