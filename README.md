# crawler-android-app

XIAO ESP32S3を搭載したクローラーロボットからのリアルタイム映像ストリームを受信し、Android端末上で**YOLOv11を用いた物体検知**を行うAndroidアプリケーションです。

## 特徴
本アプリはリアルタイム性を重視し、以下の技術的工夫を行っています。

* **リアルタイム推論**: ONNX Runtimeを活用し、YOLOv11による高速な物体検知を実現。
* **ハードウェアアクセラレーション**: Android NNAPIを利用し、CPU負荷を軽減。
* **メモリ・パフォーマンス最適化**:
    * Bitmapの再利用によるGC頻度の低減。
    * フレームスキップ処理によるFPSの安定化。
    * RGB_565フォーマット利用によるメモリ効率化。
* **通信の安定性**: Wi-Fi接続監視によるカメラモジュールとの通信。

## 使用技術
* **言語**: Kotlin
* **AI推論**: ONNX Runtime
* **画像処理**: OpenCV for Android
* **モデル**: YOLOv11s

## 動作環境
* Android 7.0 (API Level 24) 以上推奨
* カメラモジュール: XIAO ESP32S3 (MJPEGストリーミング機能)

## インストールとセットアップ
1. プロジェクトを以下のコマンドでクローンします。
```bash
```git clone https://github.com/tani-ryusuke/crawler-android-app.git```

2.Android Studioを開き、「Open」または「Project from Version Control」からクローンしたフォルダを選択します。

3.Android Studioが自動的に必要なライブラリの同期（Gradle Sync）を行います。
※右上に「Sync Now」という通知が出た場合はクリックしてください。

4.アプリのモデルファイル（assets/yolo11s.onnx）は既にリポジトリに含まれていますので、追加の準備作業は不要です。

5.Android端末をPCに接続し、Android Studioの実行ボタン（緑の三角アイコン）を押してビルド・インストールします。

6.クローラーのWi-Fiに接続後、アプリを起動してください。
   
## 今後の展望
* 通信状態監視機能の実装
* 検知対象クラスのカスタマイズ機能の追加

---
*このプロジェクトは、クローラーロボットとモバイルコンピューティングの連携を目指して開発されました。*
