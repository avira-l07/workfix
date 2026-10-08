from pathlib import Path

path = Path('app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt')
s = path.read_text(encoding='utf-8')
def replace(old, new, count=1):
    global s
    assert s.count(old) >= count, old[:100]
    s = s.replace(old, new, count)

# A fast release discards its own capture before the silence gate, with no delayed engine reset.
a = s.index('        // Bumped from 200ms')
b = s.index('        updateMessage(msgId) { it.copy(state = MessageState.STT_PROCESSING', a)
short = s[a:b]
x = short.index('            scope.launch {')
y = short.index('            // Cleanly discard', x)
short = short[:x] + '            resumeContinuousIfIdle()\n' + short[y:]
s = s[:a] + s[b:]
a = s.index('        // Microphone audio remains in memory;')
s = s[:a] + short + s[a:]
replace('''            scope.launch {
                sttMutex.withLock { engine.reset() }
                if (continuousModeJob?.isActive == true && !isTtsPlaying && !savedSpeechPlayback.value.busy) {
                    continuousListenEngine.resetAndResume()
                }
            }
            updateMessage(msgId)''', '''            resumeContinuousIfIdle()
            updateMessage(msgId)''')

replace('''                    engine.feed(audio)
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val result = engine.finalizeUtterance()''', '''                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val result = sessionManager.recognizeCapture(engine, listOf(audio))''')
replace('''                    val result = engine.finalizeUtterance()''', '''                    val result = sessionManager.recognizeCapture(engine, chunksSnapshot)''')

# Keep VAD paused through decoding/translation, and never resume it over a new PTT capture.
replace('''    private fun processContinuousSegment(audio: FloatArray) {
''', '''    private fun processContinuousSegment(audio: FloatArray) {
        if (recordingJob != null || isTtsPlaying || savedSpeechPlayback.value.busy ||
            continuousModeJob?.isActive != true || sttProcessingCount.get() != 0) return
''')
for marker in ['private fun processContinuousSegment', 'fun stopRecording(msgId: Long)']:
    a = s.index(marker)
    b = s.index('        scope.launch {\n            sttMutex.withLock {\n                try {', a)
    s = s[:b] + '        sttProcessingCount.incrementAndGet()\n' + s[b:]
    b = s.index('                } finally {', b)
    e = s.index('\n                }\n            }', b)
    s = s[:b] + '''                } finally {
                    sttProcessingCount.decrementAndGet()
                    resumeContinuousIfIdle()''' + s[e:]

replace('''                        audioSource.stream.collect { samples ->
                            if (!isTtsPlaying && !savedSpeechPlayback.value.busy)''', '''                        (microphoneFrames ?: audioSource.stream).collect { samples ->
                            if (recordingJob == null && !isTtsPlaying && !savedSpeechPlayback.value.busy)''')
replace('''                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                launch {
                    continuousListenEngine.segmentEvents''', '''                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        com.itantra.core.service.OperationalForegroundService.reportContinuousFailure(
                            "Microphone unavailable: ${e.message ?: "check microphone access and retry"}")
                        com.itantra.core.service.OperationalForegroundService.stopContinuous(context)
                    }
                }
                launch {
                    continuousListenEngine.segmentEvents''')

# Both PTT and continuous processing show a language mismatch before a generic empty-transcript error.
blank = '''                    if (result.text.isBlank()) {
                        updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "No speech recognized \\u2014 try again") }
                        return@LABEL
                    }

'''
for label in ['launch', 'withLock']:
    block = blank.replace('LABEL', label)
    a = s.index(block)
    s = s[:a] + s[a:].replace(block, '', 1)
    b = s.index('                    if (result.diagnostic != null)', a)
    e = s.index('\n                    }\n', b) + len('\n                    }\n')
    s = s[:e] + '\n' + block + s[e:]

# Use the locked TTS session for every live receive/alert, and cancel capture before speaker output.
replace('''                val result = engine.synthesize(req)''', '''                val result = sessionManager.synthesizeTts(req)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()''')
replace('''        // Step 4: TTS Suppression''', '''        cancelActiveRecording()

        // Step 4: TTS Suppression''')
replace('''            sink.flushAndStop()
            android.util.Log.d("RX_TTS"''', '''            sink.flushAndStop()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            android.util.Log.d("RX_TTS"''')
replace('''                if (continuousModeJob?.isActive == true) {
                    continuousListenEngine.resetAndResume()
                }
            }
        }
    }

    /** Sends TTS_FAILED''', '''                resumeContinuousIfIdle()
            }
        }
    }

    /** Sends TTS_FAILED''')
replace('''if (!savedSpeechPlayback.value.busy && !isTtsPlaying && currentTtsJob?.isActive != true && continuousModeJob?.isActive == true)
                        continuousListenEngine.resetAndResume()''', '''if (currentTtsJob?.isActive != true) resumeContinuousIfIdle()''')
path.write_text(s, encoding='utf-8')
