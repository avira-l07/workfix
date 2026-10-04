from pathlib import Path
root = Path(__file__).resolve().parents[3]
hub = root / 'app/src/main/java/com/example/itantra/ui/screens/hub/TransceiverHubScreen.kt'
text = hub.read_text(encoding='utf-8')
text = text.replace('Color.White.copy(alpha = 0.7f)', 'ITantraColors.SurfaceWhite.copy(alpha = 0.7f)')
text = text.replace('.background(Color.White)', '.background(ITantraColors.SurfaceWhite)')
text = text.replace('if (isPttMode) Color.White', 'if (isPttMode) ITantraColors.SurfaceWhite')
text = text.replace('if (!isPttMode) Color.White', 'if (!isPttMode) ITantraColors.SurfaceWhite')
text = text.replace('tint = Color.White', 'tint = ITantraColors.OnError')
text = text.replace('Text("Authorize & Send", color = Color.White', 'Text("Authorize & Send", color = ITantraColors.OnError')
text = text.replace('buttonColors(containerColor = ITantraColors.StatusDanger)',
                    'buttonColors(containerColor = ITantraColors.StatusDanger, contentColor = ITantraColors.OnError)')
hub.write_text(text, encoding='utf-8')
chat = root / 'app/src/main/java/com/example/itantra/ui/screens/chat/DedicatedChatScreen.kt'
text = chat.read_text(encoding='utf-8').replace('if (isRecording) Color.White', 'if (isRecording) ITantraColors.OnError')
text = text.replace('if (inputText.isNotBlank() && isConnected)', 'if (inputText.isNotBlank() && isConnected && !inputTooLong)')
chat.write_text(text, encoding='utf-8')
