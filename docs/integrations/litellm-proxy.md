# LiteLLM Proxy 接 ope_opaAgent CUSTOM Provider

> **TL;DR:** ope_opaAgent 的 CUSTOM provider 是 OpenAI-compat baseURL。LiteLLM Proxy 也是 OpenAI-compat gateway。把 baseURL 指過去即可,**零 code 改動**。

LiteLLM Proxy 的價值在你已經有多家 provider 帳號(免費 tier、付費、本地 Ollama)時:
- 跨 provider 自動 failover
- 全局 RPM/TPM 配額管理
- 統一 cost 追蹤
- API 金鑰集中管理

但需要你自己跑一個 server(laptop / VPS / Tailscale 內)。如果你想要 **app 內建** failover 而不需要外掛 server,看 Settings → Provider Failover(v1.2.12+)。兩者可並用。

## Setup

### 1. 跑 LiteLLM Proxy

```bash
pip install litellm
litellm --config /path/to/config.yaml --port 4000
```

範例 `config.yaml` 混合 OpenAI + Anthropic + Gemini + Ollama:

```yaml
model_list:
  - model_name: gpt-fallback
    litellm_params:
      model: openai/gpt-5.5
      api_key: os.environ/OPENAI_API_KEY

  - model_name: claude-primary
    litellm_params:
      model: anthropic/claude-opus-4-7
      api_key: os.environ/ANTHROPIC_API_KEY

  - model_name: gemini-fast
    litellm_params:
      model: gemini/gemini-3-flash
      api_key: os.environ/GEMINI_API_KEY

  - model_name: local-ollama
    litellm_params:
      model: ollama/llama3.2:3b
      api_base: http://localhost:11434

router_settings:
  routing_strategy: simple-shuffle
  fallbacks:
    - claude-primary: ["gpt-fallback", "gemini-fast", "local-ollama"]
  num_retries: 3

litellm_settings:
  drop_params: true
```

### 2. ope_opaAgent 設定

1. Settings → Provider → CUSTOM
2. **Base URL**: `http://<your-host>:4000/v1`
   - 本機 LAN: `http://192.168.x.x:4000/v1`
   - Tailscale: `http://laptop.tail-net-id.ts.net:4000/v1`
3. **API Key**: LiteLLM 的 master key(在 LiteLLM 的 `general_settings.master_key` 裡設)。如果沒設,任意字串(LiteLLM 默認不驗證)
4. **Model ID**: 使用 `model_name` (`claude-primary`, `gpt-fallback` 等)

LiteLLM 的 fallback 會自動接管:`claude-primary` 失敗時自動切 `gpt-fallback` → `gemini-fast` → `local-ollama`。ope_opaAgent 看到的只是「這次請求成功了」。

### 3. Tailscale 推薦

如果你的 LiteLLM 跑在筆電,建議走 Tailscale:
- 不需要 port forward
- 免費 tier 個人帳號 OK
- ope_opaAgent 的 NetworkMonitor 已經 VPN-aware(v1.2.x),不會誤判離線

## 跟 In-app Failover 的差異

| 維度 | LiteLLM Proxy | App 內建 Failover (v1.2.12+) |
|---|---|---|
| 是否需要 server | 需要 | 不需要 |
| Provider 數量 | 100+ | 我們支援的 11 家 |
| 配額管理 | 細緻(per-key TPM/RPM) | 簡單(429 換下一個) |
| 統一計費 | 是 | 否 |
| 設置門檻 | 高(要 yaml config) | 低(下拉選單) |
| 適用對象 | Power user / team | 一般使用者 |

兩者可同時使用:讓 LiteLLM 處理多 provider 聚合,讓 app 內 failover 處理 LiteLLM 整體掛掉時的 fallback(指向另一個 LiteLLM 實例或 native provider)。注意 attempts 上限 — LiteLLM 自己 retry 3 次,app 端再 retry 1-2 次,尾延遲容易拉爆。

## 故障排查

- **連不到** → 檢查 LiteLLM 實際 listen interface (`--host 0.0.0.0` not `127.0.0.1`)
- **TLS 錯誤** → ope_opaAgent cleartext config 已允許 LAN/Tailscale HTTP,但生產環境建議加 reverse proxy 走 HTTPS
- **streaming 不工作** → CUSTOM provider 走 OpenAI-compat,streaming 只有 OpenAI provider 的 sendMessage 才開,LiteLLM 自動降級為 buffered
- **看不到 actual model** → 啟用 v1.2.12 的 Provider Failover 顯示 `actualProviderId` / `actualModelId`,可看到 LiteLLM 路由結果
