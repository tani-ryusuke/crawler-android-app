# 📄 Androidアプリ メイン処理のフロー解説

XIAO ESP32S3からのMJPEG映像ストリームの受信とOpenCVを用いた描画、ONNX RuntimeによるYOLOv11リアルタイム物体検知、およびUDP通信を通じた本体ステータス（RSSI・モード・ボタン・センサー）の監視。

##  処理フロー図

```mermaid
---
config:
  layout: elk
---
flowchart TD
    A[onCreate] --> B[OpenCV & ONNXモデル初期化]
    B --> C[Wi-Fi接続要求 & ネットワーク監視]
    
    C -->|接続成功| D[startStream<br/>JPEGストリーム受信]
    C -->|接続成功| E[startStatusMonitoring<br/>UDPステータス受信]
    
    D --> F[JPEGフレーム切り出し & デコード]
    F --> G{フレームスキップ判定<br/>8フレームに1回}
    G -->|Yes| H[YOLOv11推論実行<br/>runYoloInference]
    G -->|No| I[前回のバウンディングボックスを維持]
    H --> J[動的閾値判定 & NMS処理]
    J --> K[画面へバウンディングボックスを描画]
    K --> F
    
    E --> L[UDPパケット受信待ち<br/>ポート 8888 / タイムアウト2秒]
    L --> M[受信データのパース<br/>RSSI / BTN0 / MODE]
    M --> N{BTN0 立ち上がりエッジ?}
    N -->|Yes| O[停止モードトグル反転]
    N -->|No| P[アラート判定 & UI反映<br/>ステータス・色変更]
    O --> P
    P --> L
    
    style A stroke:#818cf8,fill:#eef2ff
    style B stroke:#4ade80,fill:#f0fdf4
    style C stroke:#4ade80,fill:#f0fdf4
    style D stroke:#38bdf8,fill:#f0f9ff
    style E stroke:#38bdf8,fill:#f0f9ff
    style H stroke:#a78bfa,fill:#f5f3ff
    style N stroke:#fb923c,fill:#fff7ed
    style O stroke:#f87171,fill:#fef2f2
