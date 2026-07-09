---
name: messaging-apps
description: Send and read messages in WhatsApp, LINE, Telegram via UI automation
version: "1.0"
author: ope_opaAgent Built-in
tools_required: ui, app, notifications, clipboard
---
# Messaging Apps

## Role
You help the user send and read messages in WhatsApp, LINE, and Telegram using UI automation. Since these apps don't expose APIs, we drive them through the Accessibility Service.

## Trigger Keywords
"message", "WhatsApp", "LINE", "Telegram", "text someone", "send a message", "傳訊息", "傳 Line", "發訊息"

## Prerequisites
- Accessibility Service must be enabled for ope_opaAgent
- Target messaging app must be installed and logged in

## Workflow: Send a WhatsApp Message

1. Launch WhatsApp: `app launch com.whatsapp`
2. Wait for home screen: `ui read_screen` — confirm "Chats" tab is visible
3. Tap the search icon: `ui click` on the search/magnifier element
4. Type contact name: `ui type text="<contact_name>"`
5. Read search results: `ui read_screen` — find the matching contact
6. Tap the contact: `ui click` on the matched contact row
7. Verify chat opened: `ui read_screen` — confirm the contact name is in the header
8. Type the message: `ui type text="<message>"` in the message input field
9. Send: `ui click` on the send button (green arrow icon)
10. Confirm sent: `ui read_screen` — verify message appears with a checkmark

## Workflow: Read Recent WhatsApp Messages

**Option A — From notifications (faster):**
1. `notifications read` — filter for package `com.whatsapp`
2. Summarize who sent what and when

**Option B — From the app (full history):**
1. `app launch com.whatsapp`
2. `ui read_screen` — read the chat list showing recent conversations
3. Tap a specific chat to read messages: `ui click` → `ui read_screen`

## Workflow: Send a LINE Message

1. Launch LINE: `app launch jp.naver.line.android`
2. `ui read_screen` — confirm home screen loaded
3. Tap the chat tab (speech bubble icon): `ui click`
4. Tap the search bar: `ui click` on search
5. `ui type text="<contact_name>"`
6. `ui read_screen` — find matching contact
7. `ui click` on the contact
8. `ui type text="<message>"` in the input field
9. `ui click` on the send button (blue arrow)

## Workflow: Send a Telegram Message

1. Launch Telegram: `app launch org.telegram.messenger`
2. `ui read_screen` — confirm chat list visible
3. Tap the search icon: `ui click`
4. `ui type text="<contact_name>"`
5. `ui read_screen` → `ui click` on the matched contact
6. `ui type text="<message>"`
7. `ui click` on the send button (paper plane icon)

## Tips
- If the contact name is ambiguous, read results back to the user and ask which one
- Use `clipboard write` to paste long messages instead of typing character by character
- For group chats, search by group name the same way
- If the app is already open on a chat, skip the search steps — just type and send
